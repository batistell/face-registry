import os

filepath = r"o:\JavaProjects\face-registry\backend\src\test\java\com\batistell\faceregistry\DatabasePopulationTest.java"

if not os.path.exists(filepath):
    print("Arquivo não encontrado!")
    exit(1)

with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Novo código do método a ser inserido
new_method = """    @Test
    @Order(4)
    @DisplayName("4. Cadastra todos os randomusers e novos usuários de fotos")
    void registerRandomUsers() throws IOException {
        System.out.println("\\n👥 Cadastrando randomusers e novos usuários...");

        // Regex para extrair identificador, CPF e número do nome do arquivo
        // Padrão: [prefixo]_[CPF]_[Index].jpg
        Pattern pattern = Pattern.compile("([a-zA-Z0-9_\\\\-]+)_(\\\\d{11})_(\\\\d+)\\\\.jpg");

        // Coleta todos os arquivos que representam usuários de teste
        List<Path> userFiles = new ArrayList<>();

        try (Stream<Path> files = Files.list(examplesDir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> {
                     String filename = p.getFileName().toString();
                     // Deve casar com a regex geral de usuários de teste e ser .jpg
                     if (!filename.matches("[a-zA-Z0-9_\\\\-]+_\\\\d{11}_\\\\d+\\\\.jpg")) {
                         return false;
                     }
                     // Ignora famosos
                     for (String[] person : FAMOUS_PEOPLE) {
                         String famousPrefix = person[2] + "_" + person[1] + "_";
                         if (filename.toLowerCase().startsWith(famousPrefix.toLowerCase())) {
                             return false;
                         }
                     }
                     return true;
                 })
                 .sorted(Comparator.comparing(p -> {
                     // Ordena pelo número do arquivo (sufixo _N) para atribuição consistente de nomes
                     Matcher m = pattern.matcher(p.getFileName().toString());
                     if (m.matches()) return Integer.parseInt(m.group(3));
                     return 0;
                 }))
                 .forEach(userFiles::add);
        }

        int count = 0;

        for (Path photo : userFiles) {
            Matcher m = pattern.matcher(photo.getFileName().toString());
            if (!m.matches()) continue;

            String prefix = m.group(1);
            String cpf = m.group(2);
            int index = Integer.parseInt(m.group(3));
            String name;

            if (prefix.equals("randomuser_men")) {
                name = (index - 1 < MALE_NAMES.length)
                        ? MALE_NAMES[index - 1]
                        : "Usuário Masculino " + index;
            } else if (prefix.equals("randomuser_women")) {
                name = (index - 1 < FEMALE_NAMES.length)
                        ? FEMALE_NAMES[index - 1]
                        : "Usuária Feminina " + index;
            } else {
                // Formato customizado: extrai o nome a partir do nome do arquivo
                name = formatNameFromFilename(prefix);
            }

            try {
                byte[] photoBytes = Files.readAllBytes(photo);
                userService.createUser(cpf, name, photoBytes, photo.getFileName().toString());
                count++;
                registeredCount++;
                System.out.println("  ✅ " + name + " (CPF " + cpf + ") cadastrado com " + photo.getFileName().toString());
            } catch (Exception e) {
                System.out.println("  ⚠️ " + name + " (CPF " + cpf + ") — ERRO: " + e.getMessage());
            }
        }

        System.out.println("\\n📊 Total de usuários de teste/random cadastrados: " + count);
    }

    /**
     * Formata um nome de exibição amigável a partir do prefixo do arquivo (ex: adam-djili -> Adam Djili).
     */
    private String formatNameFromFilename(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "Usuário Sem Nome";
        }
        String clean = prefix.replace("-", " ").replace("_", " ");
        return java.util.Arrays.stream(clean.split("\\\\s+"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .collect(java.util.stream.Collectors.joining(" "));
    }"""

# Encontrar o método registerRandomUsers antigo e substituir
# Usando fatiamento de string baseado em marcadores bem definidos para evitar erros de regex
start_marker = '@DisplayName("4. Cadastra todos os randomusers com nomes brasileiros")'
end_marker = 'System.out.println("\\n📊 Randomusers cadastrados: " + menCount + " homens + " + womenCount + " mulheres");\n    }'

# Pode ter quebras de linha com \r\n no Windows
if start_marker not in content:
    start_marker = start_marker.replace('\n', '\r\n')
if end_marker not in content:
    end_marker = end_marker.replace('\n', '\r\n')

# Encontrar as posições de início e fim do método
start_idx = content.find(start_marker)
if start_idx == -1:
    print("Marcador de início não encontrado!")
    exit(1)

# Encontra a anotação @Test ou @Order logo antes de start_marker
test_anno_idx = content.rfind('@Test', 0, start_idx)
if test_anno_idx == -1:
    print("Anotação @Test não encontrada!")
    exit(1)

end_idx = content.find(end_marker, start_idx)
if end_idx == -1:
    # Tenta com \r\n no final
    end_marker = end_marker.replace('\n', '\r\n')
    end_idx = content.find(end_marker, start_idx)

if end_idx == -1:
    print("Marcador de fim não encontrado!")
    exit(1)

# Calcula o final real do método (fecha a chave do método na linha após o print)
end_pos = end_idx + len(end_marker)

# Substitui o bloco
new_content = content[:test_anno_idx] + new_method + content[end_pos:]

# Grava de volta
with open(filepath, 'w', encoding='utf-8') as f:
    f.write(new_content)

print("Patch aplicado com sucesso no DatabasePopulationTest.java!")
