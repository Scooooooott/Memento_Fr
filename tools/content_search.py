"""Search-key contract: compatibility forms, lowercase, Latin accent folding only."""
import unicodedata

VERSION='nfkc-lower-latin-nfd-1'

def search_key(text):
    output=[]
    latin=False
    for char in unicodedata.normalize('NFD',unicodedata.normalize('NFKC',text.strip(''.join(chr(n) for n in range(33)))).lower()):
        if unicodedata.category(char).startswith('M'):
            if not latin:
                output.append(char)
        else:
            latin=unicodedata.name(char,'').startswith('LATIN ')
            output.append(char)
    return unicodedata.normalize('NFC',''.join(output))
