"""Validation boundary shared by the forthcoming source adapters and packager."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT=Path(__file__).resolve().parents[1]
SCHEMA=json.loads((ROOT/'schemas/content-intermediate-v1.schema.json').read_text(encoding='utf-8'))
Draft202012Validator.check_schema(SCHEMA)
VALIDATOR=Draft202012Validator(SCHEMA)


def require_unique(values,label):
    values=list(values)
    if len(values)!=len(set(values)):
        raise ValueError(f'Duplicate {label}')


def validate_intermediate(content):
    VALIDATOR.validate(content)
    lexemes=content['lexemes']
    require_unique((word['uid'] for word in lexemes),'UID')
    require_unique((word['order'] for word in lexemes),'lexeme order')
    require_unique((sense['id'] for word in lexemes for sense in word['senses']),'sense ID')
    require_unique((example['id'] for word in lexemes for sense in word['senses'] for example in sense['examples']),'example ID')
    uids={word['uid'] for word in lexemes}
    for word in lexemes:
        if not word['uid'].startswith(content['target_language']+':'):
            raise ValueError('UID belongs to another target language')
        require_unique((sense['order'] for sense in word['senses']),'sense order')
        for sense in word['senses']:
            require_unique((example['order'] for example in sense['examples']),'example order')
        require_unique((form['order'] for form in word['forms']),'word form order')
        require_unique(((row['locale'],row['reading_type']) for row in word['pronunciations']),'pronunciation')
        require_unique((row['id'] for row in word['paradigms']),'paradigm ID')
        require_unique((row['order'] for row in word['paradigms']),'paradigm order')
        for paradigm in word['paradigms']:
            require_unique((form['subject_key'] for form in paradigm['forms']),'paradigm subject')
            require_unique((form['order'] for form in paradigm['forms']),'paradigm person order')
    require_unique((book['id'] for book in content['books']),'book ID')
    require_unique((book['order'] for book in content['books']),'book order')
    for book in content['books']:
        require_unique((member['uid'] for member in book['members']),'book member UID')
        require_unique((member['order'] for member in book['members']),'book member order')
        if any(member['uid'] not in uids for member in book['members']):
            raise ValueError('Book refers to unavailable content')
    require_unique((source['source_id'] for source in content['attribution']),'attribution source ID')
