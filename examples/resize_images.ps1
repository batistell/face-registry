# ==============================================================================
# Script em PowerShell para redimensionar as imagens grandes da pasta examples/
# Reduz imagens para um tamanho máximo de 800px para acelerar processamento de IA em CPU
# ==============================================================================

Add-Type -AssemblyName System.Drawing

$EXAMPLES_DIR = "o:\JavaProjects\face-registry\examples"
$maxSize = 800

# Procura arquivos de usuários de teste (índice >= 51 ou que começam com nomes de pessoas)
$files = Get-ChildItem -Path $EXAMPLES_DIR -File | Where-Object { $_.Extension -match '^\.(jpg|jpeg|png)$' }

Write-Host "Iniciando otimização de imagens em $EXAMPLES_DIR..."

$count = 0
foreach ($f in $files) {
    # Redimensiona apenas arquivos novos (índices >= 51 ou com nomes de pexels limpos)
    # Ignora os famosos e randomusers originais pequenos para poupar processamento se desejado,
    # mas redimensionar todos garante que não há arquivos excessivamente pesados!
    if ($f.Length -gt 150KB) {
        try {
            $img = [System.Drawing.Image]::FromFile($f.FullName)
            $w = $img.Width
            $h = $img.Height
            
            if ($w -gt $maxSize -or $h -gt $maxSize) {
                if ($w -gt $h) {
                    $newW = [int]$maxSize
                    $newH = [int][math]::Round(($h * $maxSize) / $w)
                } else {
                    $newH = [int]$maxSize
                    $newW = [int][math]::Round(($w * $maxSize) / $h)
                }
                
                $bmp = New-Object System.Drawing.Bitmap($newW, $newH)
                $g = [System.Drawing.Graphics]::FromImage($bmp)
                $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $g.DrawImage($img, 0, 0, $newW, $newH)
                
                $img.Dispose()
                $g.Dispose()
                
                $tempPath = $f.FullName + ".tmp"
                # Salva como Jpeg de boa qualidade
                $bmp.Save($tempPath, [System.Drawing.Imaging.ImageFormat]::Jpeg)
                $bmp.Dispose()
                
                Remove-Item $f.FullName -Force
                Rename-Item -Path $tempPath -NewName $f.Name
                $count++
                
                Write-Host "  [OK] $($f.Name) otimizada para ${newW}x${newH} ($([math]::Round((Get-Item $f.FullName).Length/1KB)) KB)"
            } else {
                $img.Dispose()
            }
        } catch {
            Write-Host "  [ERRO] Falha ao processar $($f.Name): $_"
        }
    }
}

Write-Host "Otimização concluída! $count imagens foram redimensionadas."
