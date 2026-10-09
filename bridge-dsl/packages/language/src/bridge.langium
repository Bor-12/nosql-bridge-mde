grammar Bridge

entry BridgeModel:
    'bridge' name=ID '{'
        source=Source
        target=Target
        (sourceTypes=SourceTypes)?
        (unmapped=UnmappedPolicy)?
        (nulls=NullPolicy)?
        mapping=MappingBlock
    '}'
;

SourceTypes:
    'sourceTypes' '{'
        declarations+=SourceTypeDeclaration*
    '}'
;

SourceTypeDeclaration:
    path=FieldPath types+=ID ('|' types+=ID)*
;

Source:
    'source' technology=ID '{'
        properties+=ConfigProperty*
    '}'
;

Target:
    'target' technology=ID '{'
        properties+=ConfigProperty*
    '}'
;

UnmappedPolicy:
    'unmapped' policy=('drop' | 'preserve')
;

NullPolicy:
    'nulls' policy=('preserve' | 'omit')
;

MappingBlock:
    'mapping' '{'
        mappings+=FieldMapping*
    '}'
;

FieldMapping:
    source=FieldPath '->' target=FieldPath ('using' transformation=ID)?
;

FieldPath:
    parts+=ID ('.' parts+=ID)*
;

ConfigProperty:
    name=ID value=STRING
;

hidden terminal WS: /\s+/;
terminal ID: /[_a-zA-Z][\w_]*/;
terminal INT returns number: /[0-9]+/;
terminal STRING: /"(\\.|[^"\\])*"|'(\\.|[^'\\])*'/;

hidden terminal ML_COMMENT: /\/\*[\s\S]*?\*\//;
hidden terminal SL_COMMENT: /\/\/[^\n\r]*/;