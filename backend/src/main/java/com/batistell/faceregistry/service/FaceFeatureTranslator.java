package com.batistell.faceregistry.service;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

public class FaceFeatureTranslator implements Translator<Image, float[]> {

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        // 1. Convert Image to HWC NDArray
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        
        // 2. Resize to 112x112 (standard for ArcFace/MobileFaceNet)
        array = NDImageUtils.resize(array, 112, 112);
        
        // 3. Transpose HWC -> CHW
        array = array.transpose(2, 0, 1);
        
        // 4. Convert to Float32
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }
        
        // 5. Normalization: scale pixels to [0.0, 1.0], subtract mean (0.5), divide by std (0.5019608)
        array = array.div(255.0f);
        
        NDManager manager = ctx.getNDManager();
        NDArray mean = manager.create(new float[] {0.5f, 0.5f, 0.5f}, new Shape(3, 1, 1));
        NDArray std = manager.create(new float[] {0.5019608f, 0.5019608f, 0.5019608f}, new Shape(3, 1, 1));
        
        array = array.sub(mean).div(std);
        
        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        // Output NDArray is a 1D tensor representing features. Convert to float[].
        return list.get(0).toFloatArray();
    }
}
