package com.batistell.faceregistry.service;

import com.batistell.faceregistry.exception.CustomExceptions.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.inference.Predictor;

@Service
public class FaceBiometricsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FaceBiometricsService.class);

    @Value("${face.recognition.threshold:0.60}")
    private double threshold;

    @Value("${face.recognition.conf-threshold:0.50}")
    private double confThreshConfig;

    @Value("${face.biometrics.mock:false}")
    private boolean mockConfig;

    private ZooModel<Image, DetectedObjects> detectionModel;
    private ZooModel<Image, float[]> recognitionModel;
    private boolean useMockMode = false;

    @PostConstruct
    public void init() {
        if (mockConfig) {
            this.useMockMode = true;
            log.info("Motor biométrico inicializado no modo simulado (Mock Mode) via configuração.");
            return;
        }

        // Link simbólico para libnvToolsExt
        try {
            String userHome = System.getProperty("user.home");
            java.io.File pytorchRoot = new java.io.File(userHome, ".djl.ai/pytorch");
            if (pytorchRoot.exists() && pytorchRoot.isDirectory()) {
                java.io.File[] dirs = pytorchRoot.listFiles(java.io.File::isDirectory);
                if (dirs != null) {
                    for (java.io.File dir : dirs) {
                        java.io.File targetSymlink = new java.io.File(dir, "libnvToolsExt.so.1");
                        if (!targetSymlink.exists()) {
                            java.io.File[] files = dir.listFiles((d, name) -> name.startsWith("libnvToolsExt-") && name.endsWith(".so.1"));
                            if (files != null && files.length > 0) {
                                log.info("Criando link simbólico para libnvToolsExt em {}: {} -> {}", dir.getName(), files[0].getName(), targetSymlink.getName());
                                java.nio.file.Files.createSymbolicLink(targetSymlink.toPath(), files[0].toPath().getFileName());
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Erro ao tentar criar link simbólico para libnvToolsExt: {}", ex.getMessage());
        }

        try {
            log.info("Inicializando modelos DJL REAIS para detecção e reconhecimento facial (PyTorch)...");
            
            try {
                ai.djl.engine.Engine.debugEnvironment();
            } catch (Exception ex) {
                log.warn("Erro ao executar Engine.debugEnvironment(): {}", ex.getMessage());
            }
            
            int gpuCount = ai.djl.engine.Engine.getInstance().getGpuCount();
            log.info("Quantidade de GPUs detectadas pela DJL: {}", gpuCount);
            
            ai.djl.Device device = gpuCount > 0 ? ai.djl.Device.gpu() : ai.djl.Device.cpu();
            log.info("Dispositivo selecionado para inferência: {}", device);

            // Tradutor RetinaFace
            double confThresh = confThreshConfig;
            double nmsThresh = 0.45f;
            double[] variance = {0.1f, 0.2f};
            int topK = 5000;
            int[][] scales = {{16, 32}, {64, 128}, {256, 512}};
            int[] steps = {8, 16, 32};
            FaceDetectionTranslator translator =
                    new FaceDetectionTranslator(confThresh, nmsThresh, variance, topK, scales, steps);

            Criteria<Image, DetectedObjects> detectionCriteria = Criteria.builder()
                    .setTypes(Image.class, DetectedObjects.class)
                    .optModelUrls("https://resources.djl.ai/test-models/pytorch/retinaface.zip")
                    .optModelName("retinaface")
                    .optTranslator(translator)
                    .optEngine("PyTorch")
                    .optDevice(device)
                    .build();
            
            // Tradutor FaceNet
            java.util.List<Float> meanList = java.util.Arrays.asList(
                    127.5f / 255.0f,
                    127.5f / 255.0f,
                    127.5f / 255.0f,
                    128.0f / 255.0f,
                    128.0f / 255.0f,
                    128.0f / 255.0f);
            String normalize = meanList.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));

            FaceFeatureTranslator recognitionTranslator = new FaceFeatureTranslator();

            Criteria<Image, float[]> recognitionCriteria = Criteria.builder()
                    .setTypes(Image.class, float[].class)
                    .optModelUrls("https://resources.djl.ai/test-models/pytorch/face_feature.zip")
                    .optModelName("face_feature")
                    .optEngine("PyTorch")
                    .optTranslator(recognitionTranslator)
                    .optDevice(device)
                    .build();

            this.detectionModel = ModelZoo.loadModel(detectionCriteria);
            this.recognitionModel = ModelZoo.loadModel(recognitionCriteria);
            this.useMockMode = false;
            try {
                log.info("Dispositivo do modelo de detecção facial: {}", this.detectionModel.getNDManager().getDevice());
                log.info("Dispositivo do modelo de reconhecimento facial: {}", this.recognitionModel.getNDManager().getDevice());
            } catch (Exception devEx) {
                log.warn("Não foi possível logar o dispositivo do modelo: {}", devEx.getMessage());
            }
            log.info("Motor biométrico com Modelos Reais DJL (RetinaFace e FaceNet/PyTorch) carregados com sucesso!");
        } catch (Exception e) {
            this.useMockMode = true;
            log.warn("Erro ao carregar modelos DJL reais. Usando fallback Mock. Motivo: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void close() {
        if (detectionModel != null) {
            detectionModel.close();
        }
        if (recognitionModel != null) {
            recognitionModel.close();
        }
    }

    // Valida a imagem e extrai o embedding 512-D
    public float[] extractFaceEmbedding(byte[] imageBytes, String originalFilename) {
        validateImage(imageBytes);

        if (useMockMode) {
            return processMockEmbedding(imageBytes, originalFilename);
        }

        try (Predictor<Image, DetectedObjects> detector = detectionModel.newPredictor();
             Predictor<Image, float[]> recognizer = recognitionModel.newPredictor()) {

            Image image = ImageFactory.getInstance().fromInputStream(new ByteArrayInputStream(imageBytes));
            DetectedObjects detections = detector.predict(image);
            
            int faceCount = detections.getNumberOfObjects();
            if (faceCount == 0) {
                throw new NoFaceDetectedException("Nenhum rosto foi detectado na imagem. Certifique-se de que a foto está bem iluminada, o rosto está de frente e centralizado, e remova acessórios como óculos escuros, bonés ou máscaras.");
            }
            if (faceCount > 1) {
                throw new MultipleFacesDetectedException("Múltiplos rostos detectados (" + faceCount + "). Envie uma foto com apenas 1 rosto visível.");
            }

            // Recorta o primeiro rosto
            ai.djl.modality.Classifications.Classification classification = detections.items().get(0);
            DetectedObjects.DetectedObject face = (DetectedObjects.DetectedObject) classification;
            BoundingBox box = face.getBoundingBox();
            ai.djl.modality.cv.output.Rectangle rect = box.getBounds();
            
            // Validações de enquadramento/tamanho
            double faceRatioW = rect.getWidth();
            double faceRatioH = rect.getHeight();
            if (faceRatioW < 0.15 || faceRatioH < 0.15) {
                throw new InvalidImageException("O rosto detectado está muito pequeno ou distante. Aproxime-se mais da câmera ou reenquadre a foto.");
            }

            int x = Math.max(0, (int) (rect.getX() * image.getWidth()));
            int y = Math.max(0, (int) (rect.getY() * image.getHeight()));
            int w = Math.min(image.getWidth() - x, (int) (rect.getWidth() * image.getWidth()));
            int h = Math.min(image.getHeight() - y, (int) (rect.getHeight() * image.getHeight()));
            
            Image croppedFace = image.getSubImage(x, y, w, h);
            float[] rawEmbedding = recognizer.predict(croppedFace);
            return normalize(rawEmbedding);

        } catch (NoFaceDetectedException | MultipleFacesDetectedException | InvalidImageException e) {
            throw e;
        } catch (Exception e) {
            log.error("Erro no pipeline de IA da DJL: {}", e.getMessage(), e);
            throw new BiometricProcessingException(
                    "Erro no processamento biométrico da imagem. O motor de IA não conseguiu processar a requisição. "
                    + "Tente novamente em alguns instantes ou entre em contato com o administrador do sistema.", e);
        }
    }

    // Similaridade de cosseno
    public double calculateCosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null) return 0.0;
        if (vectorA.length != vectorB.length) return 0.0;

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // Produto escalar (equivalente a cosseno para vetores normalizados)
    public double calculateDotProduct(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null) return 0.0;
        if (vectorA.length != vectorB.length) return 0.0;

        float dotProduct = 0.0f;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
        }
        return dotProduct;
    }

    // Normalização L2 do vetor
    public float[] normalize(float[] embedding) {
        if (embedding == null) return null;
        double sumSquare = 0.0;
        for (float val : embedding) {
            sumSquare += val * val;
        }
        double norm = Math.sqrt(sumSquare);
        if (norm > 0) {
            float[] normalized = new float[embedding.length];
            for (int i = 0; i < embedding.length; i++) {
                normalized[i] = (float) (embedding[i] / norm);
            }
            return normalized;
        }
        return embedding;
    }

    public boolean isMatch(double similarity) {
        return similarity >= this.threshold;
    }

    public double getThreshold() {
        return this.threshold;
    }

    // --- Métodos Auxiliares e Validações ---

    private void validateImage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidImageException("Arquivo de imagem vazio ou ausente.");
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                throw new InvalidImageException("Arquivo inválido. A imagem está corrompida ou o formato não é suportado.");
            }
        } catch (IOException e) {
            throw new InvalidImageException("Erro de leitura ao decodificar arquivo de imagem.");
        }
    }

    private float[] processMockEmbedding(byte[] bytes, String filename) {
        String nameLower = filename != null ? filename.toLowerCase() : "";

        // Simulações de erros biológicos para testes
        if (nameLower.contains("noface") || nameLower.contains("sem_rosto") || nameLower.contains("sem-rosto")) {
            throw new NoFaceDetectedException("Simulação: Nenhum rosto detectado na foto.");
        }
        if (nameLower.contains("multiple") || nameLower.contains("multiplos") || nameLower.contains("grupo")) {
            throw new MultipleFacesDetectedException("Simulação: Múltiplos rostos detectados. Envie foto de apenas 1 pessoa.");
        }

        return generateDeterministicMockEmbedding(bytes);
    }

    private float[] generateDeterministicMockEmbedding(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            
            // Seed estável a partir do hash SHA-256
            long seed = 0;
            for (int i = 0; i < Math.min(8, hash.length); i++) {
                seed = (seed << 8) | (hash[i] & 0xFF);
            }

            Random rand = new Random(seed);
            float[] embedding = new float[512];
            double sumSquare = 0.0;

            // Gera floats de distribuição normal
            for (int i = 0; i < 512; i++) {
                embedding[i] = (float) rand.nextGaussian();
                sumSquare += embedding[i] * embedding[i];
            }

            // Normalização L2
            double norm = Math.sqrt(sumSquare);
            if (norm > 0) {
                for (int i = 0; i < 512; i++) {
                    embedding[i] /= norm;
                }
            }

            return embedding;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 não disponível no Java Runtime.", e);
        }
    }
}
