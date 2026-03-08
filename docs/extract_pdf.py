import sys

def extract_text(pdf_path):
    try:
        import PyPDF2
        with open(pdf_path, 'rb') as f:
            reader = PyPDF2.PdfReader(f)
            text = ''
            for page in reader.pages:
                text += page.extract_text() + '\n'
            print(text)
            return
    except ImportError:
        pass

    try:
        import fitz
        doc = fitz.open(pdf_path)
        text = ''
        for page in doc:
            text += page.get_text() + '\n'
        print(text)
        return
    except ImportError:
        pass
        
    print("Error: Please install PyPDF2 or PyMuPDF (fitz)")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        extract_text(sys.argv[1])
    else:
        print("Usage: python extract_pdf.py <pdf_path>")
