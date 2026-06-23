# ==============================================================================
# Script para renomear os novos arquivos pexels para incluir o nome limpo da pessoa
# ==============================================================================

$EXAMPLES_DIR = "o:\JavaProjects\face-registry\examples"

# Nomes originais dos 44 arquivos do pexels na ordem alfabética original
$PEXELS_ORIGINAL = @(
    "pexels-adam-djili-285176437-15629261.jpg",
    "pexels-adrienne-andersen-1174503-2661256.jpg",
    "pexels-alejandro-peralta-33843739-7573733.jpg",
    "pexels-alirezazare-2965690.jpg",
    "pexels-anastasia-shuraeva-5704852.jpg",
    "pexels-angela-roma-7479983.jpg",
    "pexels-anntarazevich-4926674.jpg",
    "pexels-aog-pixels-263452684-12698454.jpg",
    "pexels-avonnephoto-3916455.jpg",
    "pexels-barcelos_fotos-1484908-2859616.jpg",
    "pexels-bestbe-models-975242-2100650.jpg",
    "pexels-breston-kenya-477564-3058391.jpg",
    "pexels-cottonbro-4153800.jpg",
    "pexels-cottonbro-4974360.jpg",
    "pexels-cottonbro-7520771.jpg",
    "pexels-davdkuko-2743754.jpg",
    "pexels-david-florin-1326024-2553653.jpg",
    "pexels-gipain-2587112.jpg",
    "pexels-iamikeee-2709388.jpg",
    "pexels-ibadat-singh-1338372-2586823.jpg",
    "pexels-ivan-s-5514883.jpg",
    "pexels-jessicanunes-2240173.jpg",
    "pexels-julia-kuzenkov-442028-2817080.jpg",
    "pexels-kalyn-kostov-1865576-3460478.jpg",
    "pexels-kevinbidwell-2380794.jpg",
    "pexels-lolarussian-1804514.jpg",
    "pexels-luri-2383227.jpg",
    "pexels-marcelo-moreira-988124-2125067.jpg",
    "pexels-mohamedelaminemsiouri-2108843.jpg",
    "pexels-murat-esibatir-156560-4355345.jpg",
    "pexels-m-y-dogar-2968692-4556737.jpg",
    "pexels-nicole-berro-991141-1994818.jpg",
    "pexels-ninazey-15602314.jpg",
    "pexels-renzylaurel-2906635.jpg",
    "pexels-rquiros-1727273.jpg",
    "pexels-sam-lion-6001808.jpg",
    "pexels-slaytinaaaa-3008355.jpg",
    "pexels-steinportraits-2110858.jpg",
    "pexels-streetwindy-2663803.jpg",
    "pexels-tkirkgoz-14697557.jpg",
    "pexels-visoesdomundo-2345374.jpg",
    "pexels-visoesdomundo-3586798.jpg",
    "pexels-wesrocha36-3797438.jpg",
    "pexels-xperimental-6934325.jpg"
)

# Encontrar os arquivos físicos gerados a partir do index 51
$pattern = "^randomuser_(men|women)_(\d{11})_(\d+)\.jpg$"
$files = Get-ChildItem -Path $EXAMPLES_DIR -File | Where-Object { $_.Name -match $pattern }

$activeFiles = @()
foreach ($f in $files) {
    if ($f.Name -match $pattern) {
        $gender = $Matches[1]
        $cpf = $Matches[2]
        $index = [int]$Matches[3]
        if ($index -ge 51) {
            $activeFiles += @{ File = $f; Cpf = $cpf; Index = $index; Gender = $gender }
        }
    }
}

# Ordena primeiro pelo índice, depois com 'men' antes de 'women'
$sortedFiles = $activeFiles | Sort-Object Index, @{ Expression = { $_.Gender }; Descending = $true }

if ($sortedFiles.Count -ne $PEXELS_ORIGINAL.Count) {
    Write-Host "AVISO: O número de arquivos no diretório ($($sortedFiles.Count)) difere dos originais ($($PEXELS_ORIGINAL.Count))!"
}

# Limpa o nome do arquivo pexels original para extrair o nome da pessoa
function Get-CleanName {
    param([string]$filename)
    # Remove prefixo
    $name = $filename -replace '^pexels-', ''
    # Remove extensão
    $name = $name -replace '\.[^.]+$', ''
    # Substitui underscores por hífens para padronizar
    $name = $name -replace '_', '-'
    # Remove números no fim precedidos por hífen (ex: -285176437-15629261 ou -2965690)
    $name = $name -replace '-\d+', ''
    # Remove dígitos avulsos no fim (ex: wesrocha36 -> wesrocha)
    $name = $name -replace '\d+', ''
    # Remove hífen restante no fim se houver
    $name = $name -replace '-$', ''
    return $name
}

for ($i = 0; $i -lt $sortedFiles.Count; $i++) {
    if ($i -ge $PEXELS_ORIGINAL.Count) {
        Write-Host "Índice excedeu a lista original!"
        break
    }
    
    $entry = $sortedFiles[$i]
    $originalName = $PEXELS_ORIGINAL[$i]
    
    # Extrai o nome da pessoa limpo
    $cleanName = Get-CleanName -filename $originalName
    
    # Novo nome do arquivo: nome-da-pessoa_CPF_Index.jpg
    $newName = "${cleanName}_$($entry.Cpf)_$($entry.Index).jpg"
    
    Write-Host "Renomeando: $($entry.File.Name) -> $newName"
    Rename-Item -Path $entry.File.FullName -NewName $newName
}

Write-Host "Renomeação para nomes de fotos concluída com sucesso!"
