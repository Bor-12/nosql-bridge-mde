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
});
