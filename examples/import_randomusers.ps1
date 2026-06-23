# ==============================================================================
# Script de importação de randomusers para o banco de dados via API REST
# Exclui randomusers antigos, cadastra novos com nomes brasileiros e CPFs
# extraídos dos nomes dos arquivos em examples/
# ==============================================================================

$ErrorActionPreference = "Stop"
$API_BASE = "http://localhost:8080/api/users"
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
# Passo 1: Excluir randomusers antigos do banco
# -----------------------------------------------
Write-Output "`n[1/3] Excluindo randomusers antigos do banco..."

$existingUsers = Invoke-RestMethod -Uri $API_BASE -Method GET -TimeoutSec 15
$deletedCount = 0
foreach ($user in $existingUsers) {
    if ($user.name -match "Teste RandomUser" -or $user.name -eq "test 123") {
        try {
            Invoke-RestMethod -Uri "$API_BASE/$($user.cpf)" -Method DELETE -TimeoutSec 10
            $deletedCount++
            Write-Output "  Excluído: $($user.name) (CPF $($user.cpf))"
        } catch {
            Write-Output "  ERRO ao excluir CPF $($user.cpf): $_"
        }
    }
}
Write-Output "  Total excluídos: $deletedCount"

# -----------------------------------------------
# Passo 2: Escanear arquivos de randomusers
# -----------------------------------------------
Write-Output "`n[2/3] Escaneando arquivos em $EXAMPLES_DIR..."

$pattern = "^randomuser_(men|women)_(\d{11})_(\d+)\.jpg$"
$files = Get-ChildItem -Path $EXAMPLES_DIR -File | Where-Object { $_.Name -match $pattern } | Sort-Object Name

$menFiles = @()
$womenFiles = @()
foreach ($f in $files) {
    if ($f.Name -match $pattern) {
        $gender = $Matches[1]
        $cpf = $Matches[2]
        $index = [int]$Matches[3]
        $entry = @{ File = $f; Cpf = $cpf; Index = $index }
        if ($gender -eq "men") { $menFiles += $entry }
        else { $womenFiles += $entry }
    }
}

# Ordena pelo índice para atribuição consistente de nomes
$menFiles = $menFiles | Sort-Object { $_.Index }
$womenFiles = $womenFiles | Sort-Object { $_.Index }

Write-Output "  Encontrados: $($menFiles.Count) homens, $($womenFiles.Count) mulheres"

# -----------------------------------------------
# Passo 3: Cadastrar via API REST
# -----------------------------------------------
Write-Output "`n[3/3] Cadastrando randomusers com nomes brasileiros...`n"

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

# Cadastra homens
for ($i = 0; $i -lt $menFiles.Count; $i++) {
    $entry = $menFiles[$i]
    $name = if ($entry.Index -le $MALE_NAMES.Count) { $MALE_NAMES[$entry.Index - 1] } else { "Homem $($entry.Index)" }
    
    try {
        $result = Register-User -Cpf $entry.Cpf -Name $name -FilePath $entry.File.FullName
        $successCount++
        Write-Output "  [M] $name (CPF $($entry.Cpf)) - OK"
    } catch {
        $errorCount++
        $errMsg = if ($_.ErrorDetails.Message) { ($_.ErrorDetails.Message | ConvertFrom-Json).message } else { $_.Exception.Message }
        Write-Output "  [M] $name (CPF $($entry.Cpf)) - ERRO: $errMsg"
    }
}

# Cadastra mulheres
for ($i = 0; $i -lt $womenFiles.Count; $i++) {
    $entry = $womenFiles[$i]
    $name = if ($entry.Index -le $FEMALE_NAMES.Count) { $FEMALE_NAMES[$entry.Index - 1] } else { "Mulher $($entry.Index)" }
    
    try {
        $result = Register-User -Cpf $entry.Cpf -Name $name -FilePath $entry.File.FullName
        $successCount++
        Write-Output "  [F] $name (CPF $($entry.Cpf)) - OK"
    } catch {
        $errorCount++
        $errMsg = if ($_.ErrorDetails.Message) { ($_.ErrorDetails.Message | ConvertFrom-Json).message } else { $_.Exception.Message }
        Write-Output "  [F] $name (CPF $($entry.Cpf)) - ERRO: $errMsg"
    }
}

# -----------------------------------------------
# Resumo
# -----------------------------------------------
$finalUsers = Invoke-RestMethod -Uri $API_BASE -Method GET -TimeoutSec 10

Write-Output "`n================================================================"
Write-Output "  RESUMO"
Write-Output "================================================================"
Write-Output "  Excluídos do banco:    $deletedCount"
Write-Output "  Cadastrados com sucesso: $successCount"
Write-Output "  Erros:                 $errorCount"
Write-Output "  Total no banco agora:  $($finalUsers.Count)"
Write-Output "================================================================"
