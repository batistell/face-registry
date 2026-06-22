import os
import shutil

# Mapeamento de prefixos de imagem para a pessoa registrada e seu CPF
mapping = {
    "obama1.jpg": ("Barack Obama", "64657075942"),
    "obama2.jpg": ("Barack Obama", "64657075942"),
    "obama3.jpg": ("Barack Obama", "64657075942"),
    "obama_alt2.jpg": ("Barack Obama", "64657075942"),
    "musk2.jpg": ("Elon Musk", "92230775596"),
    "musk3.jpg": ("Elon Musk", "92230775596"),
    "musk_alt2.jpg": ("Elon Musk", "92230775596"),
    "messi2.jpg": ("Lionel Messi", "74394356644"),
    "messi3.jpg": ("Lionel Messi", "74394356644"),
    "messi_alt2.jpg": ("Lionel Messi", "74394356644"),
    "biden1.jpg": ("Joe Biden", "21603180001"),
    "biden2.jpg": ("Joe Biden", "21603180001"),
    "biden_alt2.jpg": ("Joe Biden", "21603180001"),
    "kana2.jpg": ("Kana", "62079744844"),
    "kana3.jpg": ("Kana", "62079744844"),
    "kana4.webp": ("Kana", "62079744844"),
    "kana5.jpg": ("Kana", "62079744844"),
    "kana_alt2.jpg": ("Kana", "62079744844"),
    "lena.jpg": ("Lena", "83992409821"),
    "lena2.png": ("Lena", "83992409821"),
    "lena_alt2.png": ("Lena", "83992409821"),
}

def main():
    src_dir = os.path.dirname(os.path.abspath(__file__))
    dest_dir = os.path.join(src_dir, "to-compare")
    
    if not os.path.exists(dest_dir):
        os.makedirs(dest_dir)
        print(f"Created directory: {dest_dir}")
        
    print("Copying alternative images for 1:1 comparison testing...")
    
    copied_count = 0
    for filename, (name, cpf) in mapping.items():
        src_path = os.path.join(src_dir, filename)
        if os.path.exists(src_path):
            # Formata o nome do arquivo de destino para conter o nome da pessoa, o CPF e o nome do arquivo original
            base_name, extension = os.path.splitext(filename)
            dest_filename = f"{name} (CPF {cpf}) - {base_name}{extension}"
            dest_path = os.path.join(dest_dir, dest_filename)
            
            shutil.copy2(src_path, dest_path)
            print(f"Copied: {filename} -> {dest_filename}")
            copied_count += 1
        else:
            print(f"Source file not found: {filename}")
            
    print(f"\nCompleted! Copied {copied_count} files to {dest_dir}.")

if __name__ == '__main__':
    main()
