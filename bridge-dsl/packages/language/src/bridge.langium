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
    source=FieldPath '->' target=FieldPath ('using' transformation=ID)? ('default' defaultValue=DefaultValue)?
;

DefaultValue:
    StringDefaultValue | DecimalDefaultValue | IntegerDefaultValue | BooleanDefaultValue
;

StringDefaultValue:
    value=STRING
;

IntegerDefaultValue:
    value=(INT | NEG_INT)
;

DecimalDefaultValue:
    value=DECIMAL
;

BooleanDefaultValue:
    value=('true' | 'false')
;

FieldPath:
    parts+=ID ('.' parts+=ID)*
;

ConfigProperty:
    name=ID value=STRING
;

hidden terminal WS: /\s+/;
terminal ID: /[_a-zA-Z][\w_]*/;
terminal DECIMAL returns number: /-?[0-9]+\.[0-9]+/;
terminal NEG_INT returns number: /-[0-9]+/;
terminal INT returns number: /[0-9]+/;
terminal STRING: /"(\\.|[^"\\])*"|'(\\.|[^'\\])*'/;

hidden terminal ML_COMMENT: /\/\*[\s\S]*?\*\//;
hidden terminal SL_COMMENT: /\/\/[^\n\r]*/;
