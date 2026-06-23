# ==============================================================================
# Script para normalizar as imagens do Pexels adicionadas na pasta examples/
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

# Coletar todos os arquivos pexels-*.jpg
$pexelsFiles = Get-ChildItem -Path $EXAMPLES_DIR -Filter "pexels-*.jpg" | Sort-Object Name

if ($pexelsFiles.Count -eq 0) {
    Write-Host "Nenhum arquivo pexels-*.jpg encontrado em $EXAMPLES_DIR"
    exit 0
}

Write-Host "Encontrados $($pexelsFiles.Count) arquivos pexels para normalizar..."

# Índices iniciais para novos randomusers (os existentes vão até 50)
$menIndex = 51
$womenIndex = 51

$usedCpfs = @{}

# Carrega CPFs já existentes na pasta para evitar duplicatas
Get-ChildItem -Path $EXAMPLES_DIR -File | ForEach-Object {
    if ($_.Name -match "_(\d{11})_") {
        $usedCpfs[$Matches[1]] = $true
    }
}

$alternator = $true # Alterna entre homem (true) e mulher (false)

foreach ($file in $pexelsFiles) {
    # Gera CPF único
    do {
        $cpf = New-ValidCpf
    } while ($usedCpfs.ContainsKey($cpf))
    $usedCpfs[$cpf] = $true

    # Define gênero e índice
    if ($alternator) {
        $gender = "men"
        $index = $menIndex
        $menIndex++
    } else {
        $gender = "women"
        $index = $womenIndex
        $womenIndex++
    }
    
    $alternator = -not $alternator

    $newName = "randomuser_${gender}_${cpf}_${index}.jpg"
    $destination = Join-Path $EXAMPLES_DIR $newName

    Write-Host "Renomeando: $($file.Name) -> $newName"
    Rename-Item -Path $file.FullName -NewName $newName
}

Write-Host "Normalização concluída com sucesso!"
