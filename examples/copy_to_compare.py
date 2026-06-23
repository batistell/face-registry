"""
Script utilitário para copiar fotos alternativas de celebridades para o diretório
'to-compare/', nomeando-as com o padrão: NomePessoa (CPF XXXXXXXXXXX) - arquivo_original.ext

Estas cópias são usadas para testar a Verificação Facial 1:1 no frontend,
permitindo que o avaliador cole o CPF diretamente na interface.
"""

import os
import shutil

# Mapeamento: nome_do_arquivo -> (nome_da_pessoa, cpf_cadastrado)
# Os arquivos seguem o padrão: pessoa_cpf_N.ext
PHOTO_MAPPING = {
    "obama_64657075942_2.jpg":   ("Barack Obama", "64657075942"),
    "obama_64657075942_3.jpg":   ("Barack Obama", "64657075942"),
    "obama_64657075942_4.jpg":   ("Barack Obama", "64657075942"),
    "obama_64657075942_alt.jpg": ("Barack Obama", "64657075942"),
    "musk_92230775596_2.jpg":    ("Elon Musk",    "92230775596"),
    "musk_92230775596_3.jpg":    ("Elon Musk",    "92230775596"),
    "musk_92230775596_alt.jpg":  ("Elon Musk",    "92230775596"),
    "messi_74394356644_2.jpg":   ("Lionel Messi", "74394356644"),
    "messi_74394356644_3.jpg":   ("Lionel Messi", "74394356644"),
    "messi_74394356644_alt.jpg": ("Lionel Messi", "74394356644"),
    "biden_21603180001_2.jpg":   ("Joe Biden",    "21603180001"),
    "biden_21603180001_3.jpg":   ("Joe Biden",    "21603180001"),
    "biden_21603180001_alt.jpg": ("Joe Biden",    "21603180001"),
    "kana_62079744844_2.jpg":    ("Kana",         "62079744844"),
    "kana_62079744844_3.jpg":    ("Kana",         "62079744844"),
    "kana_62079744844_4.webp":   ("Kana",         "62079744844"),
    "kana_62079744844_5.jpg":    ("Kana",         "62079744844"),
    "kana_62079744844_alt.jpg":  ("Kana",         "62079744844"),
    "lena_83992409821_1.jpg":    ("Lena",         "83992409821"),
    "lena_83992409821_2.png":    ("Lena",         "83992409821"),
    "lena_83992409821_alt.png":  ("Lena",         "83992409821"),
}


def main():
    src_dir = os.path.dirname(os.path.abspath(__file__))
    dest_dir = os.path.join(src_dir, "to-compare")

    if not os.path.exists(dest_dir):
        os.makedirs(dest_dir)
        print(f"Created directory: {dest_dir}")

    print("Copying alternative images for 1:1 comparison testing...\n")

    copied_count = 0
    for filename, (name, cpf) in PHOTO_MAPPING.items():
        src_path = os.path.join(src_dir, filename)
        if os.path.exists(src_path):
            # Formato de destino: "NomePessoa (CPF XXXXXXXXXXX) - arquivo_original.ext"
            base_name, extension = os.path.splitext(filename)
            dest_filename = f"{name} (CPF {cpf}) - {base_name}{extension}"
            dest_path = os.path.join(dest_dir, dest_filename)

            shutil.copy2(src_path, dest_path)
            print(f"  ✓ {filename} -> {dest_filename}")
            copied_count += 1
        else:
            print(f"  ✗ Source not found: {filename}")

    print(f"\nCompleted! Copied {copied_count} files to {dest_dir}.")


if __name__ == "__main__":
    main()
