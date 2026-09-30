import type { BridgeModel } from '../src/generated/ast.js';
import { createBridgeServices } from '../src/bridge-module.js';
import { createExecutionPlan } from '../src/execution-plan.js';
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

async function generatePlan(input: string) {
    const document = await parse(input);
    expect(document.parseResult.lexerErrors).toHaveLength(0);
    expect(document.parseResult.parserErrors).toHaveLength(0);
    return createExecutionPlan(document.parseResult.value);
}

describe('BridgeExecutionPlan generation', () => {
    it('generates the bridge name', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {}
            }
        `);

        expect(plan.bridgeName).toBe('ProductBridge');
    });

    it('generates the source plan and normalizes its technology', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB { collection "products" }
                target DynamoDB {}
                mapping {}
            }
        `);

        expect(plan.source).toEqual({
            technology: 'mongodb',
            collection: 'products'
        });
    });

    it('generates the target plan', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {
                    table "Products"
                    partitionKey "id"
                }
                mapping {}
            }
        `);

        expect(plan.target).toEqual({
            technology: 'dynamodb',
            table: 'Products',
            partitionKey: 'id'
        });
    });

    it('copies target properties generically from the AST', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target CustomStore {
                    endpoint "local"
                    namespace "catalog"
                }
                mapping {}
            }
        `);

        expect(plan.target).toEqual({
            technology: 'customstore',
            endpoint: 'local',
            namespace: 'catalog'
        });
    });

    it('generates field mappings', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {
                    _id -> id
                    name -> name
                }
            }
        `);

        expect(plan.mappings).toEqual([
            { source: '_id', target: 'id' },
            { source: 'name', target: 'name' }
        ]);
    });

    it('joins nested source field path parts', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { category.name -> category }
            }
        `);

        expect(plan.mappings[0]).toEqual({
            source: 'category.name',
            target: 'category'
        });
    });
});

describe('Bridge DSL to BridgeExecutionPlan integration', () => {
    it('generates a complete plan from a complete bridge definition', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {
                    collection "products"
                }

                target DynamoDB {
                    table "Products"
                    partitionKey "id"
                }

                mapping {
                    _id -> id
                    name -> name
                    category.name -> category
                }
            }
        `);

        expect(plan).toEqual({
            bridgeName: 'ProductBridge',
            source: {
                technology: 'mongodb',
                collection: 'products'
            },
            target: {
                technology: 'dynamodb',
                table: 'Products',
                partitionKey: 'id'
            },
            mappings: [
                { source: '_id', target: 'id' },
                { source: 'name', target: 'name' },
                { source: 'category.name', target: 'category' }
            ]
        });
    });
});
