import type { BridgeModel } from '../src/generated/ast.js';
import { createBridgeServices } from '../src/bridge-module.js';
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

describe('Bridge DSL parsing', () => {
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
