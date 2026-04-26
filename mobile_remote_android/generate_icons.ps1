# PowerShell script to generate Android launcher icons
# This script creates simple PNG icons for different densities

Write-Host "Generating Android launcher icons..."

# Define icon sizes for different densities
$iconSizes = @{
    "mipmap-mdpi" = 48
    "mipmap-hdpi" = 72
    "mipmap-xhdpi" = 96
    "mipmap-xxhdpi" = 144
    "mipmap-xxxhdpi" = 192
}

# Base directory
$resDir = "app/src/main/res"

# Function to create a simple icon using .NET Graphics (if available)
function Create-SimpleIcon {
    param(
        [string]$outputPath,
        [int]$size
    )
    
    try {
        Add-Type -AssemblyName System.Drawing
        
        # Create bitmap
        $bitmap = New-Object System.Drawing.Bitmap($size, $size)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        
        # Background circle (dark blue)
        $bgBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(26, 35, 126))
        $graphics.FillEllipse($bgBrush, 4, 4, $size-8, $size-8)
        
        # Presentation screen (white rectangle)
        $screenBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
        $screenX = [int]($size * 0.25)
        $screenY = [int]($size * 0.29)
        $screenW = [int]($size * 0.5)
        $screenH = [int]($size * 0.33)
        $graphics.FillRectangle($screenBrush, $screenX, $screenY, $screenW, $screenH)
        
        # Remote control (smaller white rectangle)
        $remoteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
        $remoteX = [int]($size * 0.375)
        $remoteY = [int]($size * 0.69)
        $remoteW = [int]($size * 0.25)
        $remoteH = [int]($size * 0.17)
        $graphics.FillRectangle($remoteBrush, $remoteX, $remoteY, $remoteW, $remoteH)
        
        # Remote buttons (small circles)
        $buttonBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(26, 35, 126))
        $buttonSize = [int]($size * 0.04)
        $button1X = [int]($size * 0.42)
        $button2X = [int]($size * 0.48)
        $button3X = [int]($size * 0.54)
        $buttonY = [int]($size * 0.75)
        
        $graphics.FillEllipse($buttonBrush, $button1X, $buttonY, $buttonSize, $buttonSize)
        $graphics.FillEllipse($buttonBrush, $button2X, $buttonY, $buttonSize, $buttonSize)
        $graphics.FillEllipse($buttonBrush, $button3X, $buttonY, $buttonSize, $buttonSize)
        
        # Connection indicator (green dot)
        $greenBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(76, 175, 80))
        $dotSize = [int]($size * 0.06)
        $dotX = [int]($size * 0.47)
        $dotY = [int]($size * 0.67)
        $graphics.FillEllipse($greenBrush, $dotX, $dotY, $dotSize, $dotSize)
        
        # Save the image
        $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
        
        # Cleanup
        $graphics.Dispose()
        $bitmap.Dispose()
        $bgBrush.Dispose()
        $screenBrush.Dispose()
        $remoteBrush.Dispose()
        $buttonBrush.Dispose()
        $greenBrush.Dispose()
        
        Write-Host "Created: $outputPath ($size x $size)"
        return $true
    }
    catch {
        Write-Host "Error creating icon: $_"
        return $false
    }
}

# Generate icons for each density
foreach ($density in $iconSizes.Keys) {
    $size = $iconSizes[$density]
    $outputDir = "$resDir/$density"
    $outputPath = "$outputDir/ic_launcher.png"
    
    # Ensure directory exists
    if (!(Test-Path $outputDir)) {
        New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    }
    
    # Create the icon
    $success = Create-SimpleIcon -outputPath $outputPath -size $size
    
    if (!$success) {
        Write-Host "Failed to create $outputPath - you may need to create this manually"
    }
}

Write-Host "Icon generation complete!"
Write-Host ""
Write-Host "Note: If any icons failed to generate, you can:"
Write-Host "1. Use Android Studio's Image Asset Studio (recommended)"
Write-Host "2. Use online icon generators like https://romannurik.github.io/AndroidAssetStudio/"
Write-Host "3. Manually create PNG files with the following sizes:"
foreach ($density in $iconSizes.Keys) {
    Write-Host "   $density/ic_launcher.png - $($iconSizes[$density])x$($iconSizes[$density]) pixels"
}