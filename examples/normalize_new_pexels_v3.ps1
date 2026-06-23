# ==============================================================================
# Script para normalizar as imagens do Pexels adicionadas na pasta examples/
# Nome final do arquivo: [nome-limpo]_[CPF]_[Index].jpg
# ==============================================================================

$EXAMPLES_DIR = "o:\JavaProjects\face-registry\examples"

function New-ValidCpf {
    do {
        $digits = @()
        for ($i = 0; $i -lt 9; $i++) { $digits += Get-Random -Minimum 0 -Maximum 10 }
        
        # Primeiro dígito verificador
        $sum = 0
        for ($i = 0; $i -lt 9; $i++) { $sum += $digits[$i] * (10 - $i) }
        $rem = $sum % 11
        $d1 = if ($rem -lt 2) { 0 } else { 11 - $rem }
        $digits += $d1
        
        # Segundo dígito verificador
        $sum = 0
        for ($i = 0; $i -lt 10; $i++) { $sum += $digits[$i] * (11 - $i) }
        $rem = $sum % 11
        $d2 = if ($rem -lt 2) { 0 } else { 11 - $rem }
        $digits += $d2
        
        $cpf = $digits -join ""
        $allSame = ($digits | Sort-Object -Unique).Count -eq 1
    } while ($allSame)
    
    return $cpf
}

# Limpa o nome do arquivo pexels original para extrair o nome da pessoa
function Get-CleanName {
    param([string]$filename)
    $name = $filename -replace '^pexels-', ''
    $name = $name -replace '\.[^.]+$', '' # remove extensao
    $name = $name -replace '_', '-' # padroniza underscores para hifens
    $name = $name -replace '-\d+', '' # remove numeros precedidos por hifen
    $name = $name -replace '\d+', '' # remove numeros restantes
    $name = $name -replace '-$', '' # remove hifen residual no fim
    return $name
}

# Coletar todos os arquivos pexels-*.jpg
$pexelsFiles = Get-ChildItem -Path $EXAMPLES_DIR -Filter "pexels-*.jpg" | Sort-Object Name

if ($pexelsFiles.Count -eq 0) {
    Write-Host "Nenhum arquivo pexels-*.jpg encontrado em $EXAMPLES_DIR"
    exit 0
}

Write-Host "Encontrados $($pexelsFiles.Count) arquivos pexels para normalizar..."

# Carrega CPFs já existentes na pasta para evitar duplicatas
$usedCpfs = @{}
Get-ChildItem -Path $EXAMPLES_DIR -File | ForEach-Object {
    if ($_.Name -match "_(\d{11})_") {
        $usedCpfs[$Matches[1]] = $true
    }
}

# Índice inicial para novos usuários (para não colidir com o range 1-50 dos randomusers originais)
$index = 51

foreach ($file in $pexelsFiles) {
    # Gera CPF único
    do {
        $cpf = New-ValidCpf
    } while ($usedCpfs.ContainsKey($cpf))
    $usedCpfs[$cpf] = $true

    # Extrai o nome da pessoa limpo
    $cleanName = Get-CleanName -filename $file.Name

    # Novo nome do arquivo: nome-da-pessoa_CPF_Index.jpg
    $newName = "${cleanName}_${cpf}_${index}.jpg"
    
    Write-Host "Renomeando: $($file.Name) -> $newName"
    Rename-Item -Path $file.FullName -NewName $newName
    
    $index++
}

Write-Host "Normalização concluída com sucesso!"
