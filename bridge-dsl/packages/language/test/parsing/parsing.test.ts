import type { BridgeModel, DefaultValue } from '../../src/generated/ast.js';
import { createBridgeServices } from '../../src/bridge-module.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, parseHelper } from 'langium/test';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';

let services: ReturnType<typeof createBridgeServices>;
let parse: ReturnType<typeof parseHelper<BridgeModel>>;

beforeAll(() => {
    services = createBridgeServices(EmptyFileSystem);
    parse = parseHelper<BridgeModel>(services.Bridge);
});

afterEach(async () => {
    await clearDocuments(services.shared);
});

async function parseBridge(input: string): Promise<BridgeModel> {
    const document = await parse(input);
    expect(document.parseResult.lexerErrors).toHaveLength(0);
    expect(document.parseResult.parserErrors).toHaveLength(0);
    return document.parseResult.value;
}

function defaultSummary(defaultValue: DefaultValue | undefined) {
    if (!defaultValue) {
        return undefined;
    }
    switch (defaultValue.$type) {
        case 'StringDefaultValue':
        case 'IntegerDefaultValue':
        case 'DecimalDefaultValue':
        case 'BooleanDefaultValue':
            return {
                type: defaultValue.$type,
                value: defaultValue.value
            };
    }
}

describe('Bridge DSL parsing', () => {
    it.each([undefined, 'toString', 'toInteger', 'toDecimal', 'foo'])('parses a mapping with transformation %s', async transformation => {
        const model = await parseBridge(`
            bridge TransformationBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { price -> amount ${transformation === undefined ? '' : `using ${transformation}`} }
            }
        `);

        expect(model.mapping.mappings[0].source.parts).toEqual(['price']);
        expect(model.mapping.mappings[0].target.parts).toEqual(['amount']);
        expect(model.mapping.mappings[0].transformation).toBe(transformation);
    });

    it.each([
        { mapping: 'name -> name', expected: undefined },
        { mapping: 'status -> status default "ACTIVE"', expected: { type: 'StringDefaultValue', value: 'ACTIVE' } },
        { mapping: 'stock -> stock default 0', expected: { type: 'IntegerDefaultValue', value: 0 } },
        { mapping: 'stock -> stock default -1', expected: { type: 'IntegerDefaultValue', value: -1 } },
        { mapping: 'price -> price default 12.5', expected: { type: 'DecimalDefaultValue', value: 12.5 } },
        { mapping: 'price -> price default -0.25', expected: { type: 'DecimalDefaultValue', value: -0.25 } },
        { mapping: 'active -> active default true', expected: { type: 'BooleanDefaultValue', value: 'true' } },
        { mapping: 'active -> active default false', expected: { type: 'BooleanDefaultValue', value: 'false' } }
    ])('parses mapping default: $mapping', async ({ mapping, expected }) => {
        const model = await parseBridge(`
            bridge DefaultBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { ${mapping} }
            }
        `);

        expect(defaultSummary(model.mapping.mappings[0].defaultValue)).toEqual(expected);
    });

    it('parses a transformation followed by a default', async () => {
        const model = await parseBridge(`
            bridge DefaultBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { price -> price using toDecimal default "0" }
            }
        `);

        expect(model.mapping.mappings[0].transformation).toBe('toDecimal');
        expect(defaultSummary(model.mapping.mappings[0].defaultValue)).toEqual({
            type: 'StringDefaultValue',
            value: '0'
        });
    });

    it('keeps scalar default literal kinds distinct in the AST', async () => {
        const model = await parseBridge(`
            bridge DefaultBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {
                    stock -> stock default 0
                    price -> price default 0.0
                    code -> code default "0"
                    active -> active default false
                }
            }
        `);

        expect(model.mapping.mappings.map(mapping => defaultSummary(mapping.defaultValue))).toEqual([
            { type: 'IntegerDefaultValue', value: 0 },
            { type: 'DecimalDefaultValue', value: 0 },
            { type: 'StringDefaultValue', value: '0' },
            { type: 'BooleanDefaultValue', value: 'false' }
        ]);
    });

    it('parses mixed mappings and nested paths with transformations', async () => {
        const model = await parseBridge(`
            bridge TransformationBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {
                    _id -> id using toString
                    name -> name
                    category.name -> product.category using toString
                }
            }
        `);

        expect(model.mapping.mappings.map(mapping => ({
            source: mapping.source.parts,
            target: mapping.target.parts,
            transformation: mapping.transformation
        }))).toEqual([
            { source: ['_id'], target: ['id'], transformation: 'toString' },
            { source: ['name'], target: ['name'], transformation: undefined },
            { source: ['category', 'name'], target: ['product', 'category'], transformation: 'toString' }
        ]);
    });

    it.each([
        'price -> price using toDecimal using toString',
        'price -> price using toDecimal(2)',
        'price -> price using "toDecimal"',
        'price -> price default "0" using toDecimal',
        'price -> price default null',
        'price -> price default [1, 2]',
        'price -> price default {"active": true}',
        'firstName + lastName -> name'
    ])('rejects unsupported mapping syntax: %s', async mapping => {
        const document = await parse(`
            bridge TransformationBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { ${mapping} }
            }
        `);

        expect(document.parseResult.lexerErrors.length + document.parseResult.parserErrors.length).toBeGreaterThan(0);
    });

    it('parses the bridge name', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {}
            }
        `);

        expect(model.name).toBe('ProductBridge');
    });

    it('leaves unmapped undefined when omitted', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {}
            }
        `);

        expect(model.unmapped).toBeUndefined();
    });

    it('parses an explicit unmapped drop policy', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                unmapped drop
                mapping { name -> name }
            }
        `);

        expect(model.unmapped).toMatchObject({
            $type: 'UnmappedPolicy',
            policy: 'drop'
        });
    });

    it('parses an explicit unmapped preserve policy', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                unmapped preserve
                mapping { name -> name }
            }
        `);

        expect(model.unmapped).toMatchObject({
            $type: 'UnmappedPolicy',
            policy: 'preserve'
        });
    });

    it('rejects an unknown unmapped policy', async () => {
        const document = await parse(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                unmapped unknown
                mapping { name -> name }
            }
        `);

        expect(document.parseResult.lexerErrors).toHaveLength(0);
        expect(document.parseResult.parserErrors).not.toHaveLength(0);
    });

    it('leaves nulls undefined when omitted', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {}
            }
        `);

        expect(model.nulls).toBeUndefined();
    });

    it('parses an explicit nulls preserve policy', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                nulls preserve
                mapping { name -> name }
            }
        `);

        expect(model.nulls).toMatchObject({
            $type: 'NullPolicy',
            policy: 'preserve'
        });
    });

    it('parses an explicit nulls omit policy', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                nulls omit
                mapping { name -> name }
            }
        `);

        expect(model.nulls).toMatchObject({
            $type: 'NullPolicy',
            policy: 'omit'
        });
    });

    it('rejects an unknown nulls policy', async () => {
        const document = await parse(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                nulls unknown
                mapping { name -> name }
            }
        `);

        expect(document.parseResult.lexerErrors).toHaveLength(0);
        expect(document.parseResult.parserErrors).not.toHaveLength(0);
    });

    it('parses a generic source technology', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB { collection "products" }
                target DynamoDB {}
                mapping {}
            }
        `);

        expect(model.source.technology).toBe('MongoDB');
    });

    it('parses dynamic source properties', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB { collection "products" }
                target DynamoDB {}
                mapping {}
            }
        `);

        expect(model.source.properties).toHaveLength(1);
        expect(model.source.properties[0]).toMatchObject({
            name: 'collection',
            value: 'products'
        });
    });

    it('parses a generic target technology', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {
                    table "Products"
                    partitionKey "id"
                }
                mapping {}
            }
        `);

        expect(model.target.technology).toBe('DynamoDB');
    });

    it('parses multiple dynamic target properties', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {
                    table "Products"
                    partitionKey "id"
                }
                mapping {}
            }
        `);

        expect(model.target.properties.map(property => ({
            name: property.name,
            value: property.value
        }))).toEqual([
            { name: 'table', value: 'Products' },
            { name: 'partitionKey', value: 'id' }
        ]);
    });

    it('parses multiple field mappings', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {
                    _id -> id
                    name -> name
                    price -> price
                }
            }
        `);

        expect(model.mapping.mappings).toHaveLength(3);
    });

    it('parses a nested source field path', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { category.name -> category }
            }
        `);

        expect(model.mapping.mappings[0].source.parts).toEqual(['category', 'name']);
    });

    it('parses nested source and target field paths', async () => {
        const model = await parseBridge(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { product.name -> details.name }
            }
        `);

        expect(model.mapping.mappings[0].source.parts).toEqual(['product', 'name']);
        expect(model.mapping.mappings[0].target.parts).toEqual(['details', 'name']);
    });
});
