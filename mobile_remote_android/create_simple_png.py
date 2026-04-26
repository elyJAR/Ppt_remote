#!/usr/bin/env python3
"""
Simple script to create basic PNG launcher icons using PIL/Pillow
Run this if you have Python with Pillow installed: pip install Pillow
"""

try:
    from PIL import Image, ImageDraw
    import os
    
    def create_icon(size, output_path):
        # Create a new image with transparent background
        img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        
        # Background circle (dark blue)
        margin = size // 12
        circle_size = size - 2 * margin
        draw.ellipse([margin, margin, margin + circle_size, margin + circle_size], 
                    fill=(26, 35, 126, 255), outline=(13, 71, 161, 255), width=2)
        
        # Presentation screen (white rectangle)
        screen_margin = size // 4
        screen_width = size // 2
        screen_height = size // 3
        screen_x = screen_margin
        screen_y = size // 3.5
        draw.rectangle([screen_x, screen_y, screen_x + screen_width, screen_y + screen_height],
                      fill=(255, 255, 255, 255), outline=(26, 35, 126, 255), width=1)
        
        # Screen content (small lines)
        line_margin = screen_x + size // 16
        line_y1 = screen_y + size // 16
        line_y2 = line_y1 + size // 24
        line_y3 = line_y2 + size // 24
        
        draw.rectangle([line_margin, line_y1, line_margin + size // 6, line_y1 + size // 48],
                      fill=(26, 35, 126, 180))
        draw.rectangle([line_margin, line_y2, line_margin + size // 4, line_y2 + size // 48],
                      fill=(26, 35, 126, 180))
        draw.rectangle([line_margin, line_y3, line_margin + size // 8, line_y3 + size // 48],
                      fill=(26, 35, 126, 180))
        
        # Remote control (smaller white rectangle)
        remote_width = size // 4
        remote_height = size // 6
        remote_x = (size - remote_width) // 2
        remote_y = size * 2 // 3
        draw.rectangle([remote_x, remote_y, remote_x + remote_width, remote_y + remote_height],
                      fill=(255, 255, 255, 255), outline=(26, 35, 126, 255), width=1)
        
        # Remote buttons (small circles)
        button_size = size // 24
        button_y = remote_y + size // 24
        button1_x = remote_x + size // 32
        button2_x = remote_x + remote_width // 2 - button_size // 2
        button3_x = remote_x + remote_width - size // 32 - button_size
        
        draw.ellipse([button1_x, button_y, button1_x + button_size, button_y + button_size],
                    fill=(26, 35, 126, 255))
        draw.ellipse([button2_x, button_y, button2_x + button_size, button_y + button_size],
                    fill=(26, 35, 126, 255))
        draw.ellipse([button3_x, button_y, button3_x + button_size, button_y + button_size],
                    fill=(26, 35, 126, 255))
        
        # Connection indicator (green dot)
        dot_size = size // 16
        dot_x = (size - dot_size) // 2
        dot_y = remote_y - size // 16
        draw.ellipse([dot_x, dot_y, dot_x + dot_size, dot_y + dot_size],
                    fill=(76, 175, 80, 255))
        
        # Save the image
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        img.save(output_path, 'PNG')
        print(f"Created: {output_path} ({size}x{size})")
        
    # Icon sizes for different densities
    icon_sizes = {
        'mipmap-mdpi': 48,
        'mipmap-hdpi': 72,
        'mipmap-xhdpi': 96,
        'mipmap-xxhdpi': 144,
        'mipmap-xxxhdpi': 192
    }
    
    print("Creating PNG launcher icons...")
    
    for density, size in icon_sizes.items():
        output_path = f"app/src/main/res/{density}/ic_launcher.png"
        create_icon(size, output_path)
    
    print("PNG icon creation complete!")
    print("\nIcons created for all densities. The app should now have a custom launcher icon.")
    
except ImportError:
    print("PIL/Pillow not available. Install with: pip install Pillow")
    print("Alternatively, use Android Studio's Image Asset Studio or an online generator.")
except Exception as e:
    print(f"Error creating icons: {e}")
    print("Use Android Studio's Image Asset Studio or an online generator instead.")