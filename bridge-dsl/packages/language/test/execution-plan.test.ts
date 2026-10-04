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
    it.each(['source', 'target'] as const)('keeps a dynamic %s technology property inside config', async connector => {
        const plan = await generatePlan(`
            bridge DynamicPropertyBridge {
                source MongoDB {
                    ${connector === 'source' ? 'technology "legacy"' : ''}
                    collection "products"
                }
                target DynamoDB {
                    ${connector === 'target' ? 'technology "legacy"' : ''}
                    table "Products"
                }
                mapping { name -> name }
            }
        `);

        expect(plan.source).toEqual({
            technology: 'mongodb',
            config: {
                ...(connector === 'source' ? { technology: 'legacy' } : {}),
                collection: 'products'
            }
        });
        expect(plan.target).toEqual({
            technology: 'dynamodb',
            config: {
                ...(connector === 'target' ? { technology: 'legacy' } : {}),
                table: 'Products'
            }
        });
    });

    it('generates empty config objects for connectors without properties', async () => {
        const plan = await generatePlan(`
            bridge EmptyConfigBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { name -> name }
            }
        `);

        expect(plan.source).toEqual({ technology: 'mongodb', config: {} });
        expect(plan.target).toEqual({ technology: 'dynamodb', config: {} });
    });

    it('generates plan version 1 for every execution plan', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping {}
            }
        `);

        expect(plan.planVersion).toBe(1);
    });

    it.each([
        { name: 'implicit preserve', declaration: '', policy: 'preserve' },
        { name: 'explicit preserve', declaration: 'nulls preserve', policy: 'preserve' },
        { name: 'explicit omit', declaration: 'nulls omit', policy: 'omit' }
    ] as const)('generates $policy as the null policy for $name', async ({ declaration, policy }) => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                ${declaration}
                mapping { name -> name }
            }
        `);

        expect(plan.nullPolicy).toBe(policy);
    });

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
            config: {
                collection: 'products'
            }
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
            config: {
                table: 'Products',
                partitionKey: 'id'
            }
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
            config: {
                endpoint: 'local',
                namespace: 'catalog'
            }
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

    it('joins nested source and target field path parts', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { product.name -> details.name }
            }
        `);

        expect(plan.mappings[0]).toEqual({
            source: 'product.name',
            target: 'details.name'
        });
    });

    it('joins nested target field path parts for a simple source', async () => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {}
                target DynamoDB {}
                mapping { name -> product.name }
            }
        `);

        expect(plan.mappings[0]).toEqual({
            source: 'name',
            target: 'product.name'
        });
    });
});

describe('Bridge DSL to BridgeExecutionPlan integration', () => {
    it.each([
        { name: 'implicit drop', declaration: '', policy: 'drop' },
        { name: 'explicit drop', declaration: 'unmapped drop', policy: 'drop' },
        { name: 'explicit preserve', declaration: 'unmapped preserve', policy: 'preserve' }
    ] as const)('generates a complete plan with $name', async ({ declaration, policy }) => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {
                    database "shop"
                    collection "products"
                }

                target DynamoDB {
                    table "Products"
                    partitionKey "id"
                }

                ${declaration}

                mapping {
                    _id -> id
                    name -> name
                    category.name -> category
                }
            }
        `);

        expect(plan).toEqual({
            planVersion: 1,
            bridgeName: 'ProductBridge',
            source: {
                technology: 'mongodb',
                config: {
                    database: 'shop',
                    collection: 'products'
                }
            },
            target: {
                technology: 'dynamodb',
                config: {
                    table: 'Products',
                    partitionKey: 'id'
                }
            },
            unmappedPolicy: policy,
            nullPolicy: 'preserve',
            mappings: [
                { source: '_id', target: 'id' },
                { source: 'name', target: 'name' },
                { source: 'category.name', target: 'category' }
            ]
        });
    });

    it.each([
        { name: 'implicit preserve', declaration: '', policy: 'preserve' },
        { name: 'explicit preserve', declaration: 'nulls preserve', policy: 'preserve' },
        { name: 'explicit omit', declaration: 'nulls omit', policy: 'omit' }
    ] as const)('generates a complete plan with $name nulls', async ({ declaration, policy }) => {
        const plan = await generatePlan(`
            bridge ProductBridge {
                source MongoDB {
                    database "shop"
                    collection "products"
                }

                target DynamoDB {
                    table "Products"
                    partitionKey "id"
                }

                ${declaration}

                mapping {
                    _id -> id
                    name -> name
                    category.name -> category
                }
            }
        `);

        expect(plan.nullPolicy).toBe(policy);
        expect(plan.unmappedPolicy).toBe('drop');
    });
});
