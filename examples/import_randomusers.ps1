# ==============================================================================
# Script de importação de randomusers para o banco de dados via API REST
# Exclui randomusers antigos, cadastra novos com nomes brasileiros e CPFs
# extraídos dos nomes dos arquivos em examples/
# ==============================================================================

$ErrorActionPreference = "Stop"
$API_BASE = "http://127.0.0.1:8080/api/users"
$EXAMPLES_DIR = "o:\JavaProjects\face-registry\examples"

# Nomes brasileiros: 50 masculinos + 50 femininos
$MALE_NAMES = @(
    "João Silva", "Pedro Santos", "Lucas Oliveira", "Gabriel Souza", "Matheus Costa",
    "Rafael Pereira", "Bruno Ferreira", "André Almeida", "Thiago Rodrigues", "Felipe Lima",
    "Diego Martins", "Gustavo Nascimento", "Marcos Araújo", "Rodrigo Ribeiro", "Henrique Carvalho",
    "Daniel Gomes", "Leonardo Barbosa", "Vinícius Monteiro", "Eduardo Correia", "Carlos Cardoso",
    "Renato Teixeira", "Fábio Vieira", "Ricardo Mendes", "Paulo Moreira", "Roberto Castro",
    "Alexandre Pinto", "Fernando Rocha", "Marcelo Dias", "Leandro Campos", "Caio Nunes",
    "Hugo Machado", "Arthur Marques", "Samuel Freitas", "Otávio Lopes", "Victor Duarte",
    "Renan Moura", "William Cavalcanti", "Júlio Ramos", "Luciano Cunha", "Adriano Borges",
    "Bernardo Pires", "Matias Fonseca", "Nelson Melo", "Sérgio Rezende", "Maurício Fernandes",
    "Emerson Azevedo", "Douglas Braga", "Cristiano Xavier", "Igor Batista", "Cauan Nogueira"
)

$FEMALE_NAMES = @(
    "Ana Clara", "Maria Eduarda", "Juliana Santos", "Camila Oliveira", "Fernanda Costa",
    "Beatriz Souza", "Larissa Lima", "Patrícia Ferreira", "Amanda Almeida", "Carolina Rodrigues",
    "Isabela Martins", "Letícia Nascimento", "Mariana Araújo", "Gabriela Ribeiro", "Rafaela Carvalho",
    "Bruna Gomes", "Natália Barbosa", "Vanessa Monteiro", "Tatiana Correia", "Daniela Cardoso",
    "Priscila Teixeira", "Renata Vieira", "Aline Mendes", "Bianca Moreira", "Cristina Castro",
    "Débora Pinto", "Elaine Rocha", "Flávia Dias", "Helena Campos", "Ingrid Nunes",
    "Jéssica Machado", "Karen Marques", "Laura Andrade", "Mônica Freitas", "Nathalie Lopes",
    "Olívia Duarte", "Paula Moura", "Raquel Cavalcanti", "Sandra Ramos", "Simone Cunha",
    "Talita Borges", "Úrsula Pires", "Valéria Fonseca", "Yasmin Melo", "Zélia Rezende",
    "Adriana Fernandes", "Bárbara Azevedo", "Carla Braga", "Diana Xavier", "Érika Batista"
)

Write-Output "================================================================"
Write-Output "  IMPORTAÇÃO DE RANDOMUSERS - Face Registry"
Write-Output "================================================================"

# -----------------------------------------------
# Passo 1: Excluir randomusers antigos do banco (PULADO)
# -----------------------------------------------
$deletedCount = 0

# -----------------------------------------------
# Passo 2: Escanear arquivos de usuários de teste
# -----------------------------------------------
Write-Output "`n[2/3] Escaneando arquivos em $EXAMPLES_DIR..."

$pattern = "^([a-zA-Z0-9_\-]+)_(\d{11})_(\d+)\.jpg$"
$files = Get-ChildItem -Path $EXAMPLES_DIR -File | Where-Object { $_.Name -match $pattern }

$famousPrefixes = @("obama", "biden", "musk", "messi", "kana", "lena")
$userFiles = @()

foreach ($f in $files) {
    if ($f.Name -match $pattern) {
        $prefix = $Matches[1]
        $cpf = $Matches[2]
        $index = [int]$Matches[3]
        
        # Ignora arquivos de famosos
        if ($famousPrefixes -contains $prefix.ToLower()) {
            continue
        }
        
        $userFiles += @{ File = $f; Prefix = $prefix; Cpf = $cpf; Index = $index }
    }
}

# Ordena pelo índice para ordenação consistente no console
$userFiles = $userFiles | Sort-Object { $_.Index }

Write-Output "  Encontrados: $($userFiles.Count) usuários de teste no diretório."

# -----------------------------------------------
# Passo 3: Cadastrar via API REST
# -----------------------------------------------
Write-Output "`n[3/3] Cadastrando usuários de teste com nomes correspondentes...`n"

$successCount = 0
$errorCount = 0

# Função para cadastrar um usuário via multipart POST
function Register-User {
    param([string]$Cpf, [string]$Name, [string]$FilePath)
    
    $boundary = [System.Guid]::NewGuid().ToString()
    $LF = "`r`n"
    $fileBytes = [System.IO.File]::ReadAllBytes($FilePath)
    $fileName = [System.IO.Path]::GetFileName($FilePath)
    
    $bodyLines = @(
        "--$boundary",
        "Content-Disposition: form-data; name=`"cpf`"$LF",
        $Cpf,
        "--$boundary",
        "Content-Disposition: form-data; name=`"name`"$LF",
        $Name,
        "--$boundary",
        "Content-Disposition: form-data; name=`"photo`"; filename=`"$fileName`"",
        "Content-Type: image/jpeg$LF"
    ) -join $LF

    $headerBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyLines + $LF)
    $footerBytes = [System.Text.Encoding]::UTF8.GetBytes("$LF--$boundary--$LF")
    
    $bodyStream = New-Object System.IO.MemoryStream
    $bodyStream.Write($headerBytes, 0, $headerBytes.Length)
    $bodyStream.Write($fileBytes, 0, $fileBytes.Length)
    $bodyStream.Write($footerBytes, 0, $footerBytes.Length)
    $bodyArray = $bodyStream.ToArray()
    $bodyStream.Dispose()

    $headers = @{ "Content-Type" = "multipart/form-data; boundary=$boundary" }
    
    $response = Invoke-RestMethod -Uri $API_BASE -Method POST -Body $bodyArray -Headers $headers -TimeoutSec 30
    return $response
}

# Cadastra os usuários
foreach ($entry in $userFiles) {
    $prefix = $entry.Prefix
    $index = $entry.Index
    $cpf = $entry.Cpf
    
    $name = ""
    if ($prefix -eq "randomuser_men") {
        $name = if ($index -le $MALE_NAMES.Count) { $MALE_NAMES[$index - 1] } else { "Homem $index" }
    }
    elseif ($prefix -eq "randomuser_women") {
        $name = if ($index -le $FEMALE_NAMES.Count) { $FEMALE_NAMES[$index - 1] } else { "Mulher $index" }
    }
    else {
        # Formato customizado: extrai e formata o nome com ToTitleCase
        $cleanPrefix = $prefix -replace '[-_]', ' '
        $name = (Get-Culture).TextInfo.ToTitleCase($cleanPrefix)
    }
    
    try {
        $result = Register-User -Cpf $cpf -Name $name -FilePath $entry.File.FullName
        $successCount++
        Write-Output "  ✅ $name (CPF $cpf) - OK"
    } catch {
        $errorCount++
        $errMsg = if ($_.ErrorDetails.Message) { 
            try { ($_.ErrorDetails.Message | ConvertFrom-Json).message } catch { $_.Exception.Message }
        } else { $_.Exception.Message }
        Write-Output "  ❌ $name (CPF $cpf) - ERRO: $errMsg"
    }
}

# -----------------------------------------------
# Resumo
# -----------------------------------------------
Write-Output "`n================================================================"
Write-Output "  RESUMO"
Write-Output "================================================================"
Write-Output "  Excluídos do banco:    $deletedCount"
Write-Output "  Cadastrados com sucesso: $successCount"
Write-Output "  Erros:                 $errorCount"
Write-Output "================================================================"
