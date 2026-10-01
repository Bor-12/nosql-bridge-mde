import type { BridgeModel } from '../src/generated/ast.js';
import { createBridgeServices } from '../src/bridge-module.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, validationHelper } from 'langium/test';
import { DiagnosticSeverity } from 'vscode-languageserver-types';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';

const emptyMappingMessage = 'A bridge must contain at least one mapping.';

let services: ReturnType<typeof createBridgeServices>;
let validate: ReturnType<typeof validationHelper<BridgeModel>>;

beforeAll(() => {
    services = createBridgeServices(EmptyFileSystem);
    validate = validationHelper<BridgeModel>(services.Bridge);
});

afterEach(async () => {
    await clearDocuments(services.shared);
});

describe('Bridge DSL semantic validation', () => {
    it.each([
        {
            name: 'requires collection for MongoDB source',
            source: 'source MongoDB {}',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            messages: ["Missing required property 'collection' for MongoDB source."]
        },
        {
            name: 'accepts collection for MongoDB source',
            source: 'source MongoDB { collection "products" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            messages: []
        },
        {
            name: 'requires partitionKey for DynamoDB target',
            source: 'source MongoDB { collection "products" }',
            target: 'target DynamoDB { table "Products" }',
            messages: ["Missing required property 'partitionKey' for DynamoDB target."]
        },
        {
            name: 'accepts table and partitionKey for DynamoDB target',
            source: 'source MongoDB { collection "products" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            messages: []
        },
        {
            name: 'requires table for DynamoDB source',
            source: 'source DynamoDB {}',
            target: 'target MongoDB { collection "products" }',
            messages: ["Missing required property 'table' for DynamoDB source."]
        },
        {
            name: 'requires collection for MongoDB target',
            source: 'source DynamoDB { table "Products" }',
            target: 'target MongoDB {}',
            messages: ["Missing required property 'collection' for MongoDB target."]
        },
        {
            name: 'reports every missing property for DynamoDB target',
            source: 'source MongoDB { collection "products" }',
            target: 'target DynamoDB {}',
            messages: [
                "Missing required property 'table' for DynamoDB target.",
                "Missing required property 'partitionKey' for DynamoDB target."
            ]
        }
    ])('$name', async ({ source, target, messages }) => {
        const result = await validate(`
            bridge ConnectorBridge {
                ${source}
                ${target}
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual(messages.map(message => expect.objectContaining({
            message,
            severity: DiagnosticSeverity.Error
        })));
    });

    it.each(['source', 'target'] as const)('rejects an unknown technology in %s semantically', async connector => {
        const result = await validate(`
            bridge UnknownConnectorBridge {
                ${connector === 'source'
                    ? 'source UnknownDB { foo "bar" }'
                    : 'source MongoDB { collection "products" }'}
                ${connector === 'target'
                    ? 'target UnknownDB { foo "bar" }'
                    : 'target DynamoDB { table "Products" partitionKey "id" }'}
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: "Unknown connector technology 'UnknownDB'.",
                severity: DiagnosticSeverity.Error
            })
        ]);
    });

    it.each([
        ['mOnGoDb', 'DyNaMoDb', 'collection "products"', 'table "Products" partitionKey "id"'],
        ['DyNaMoDb', 'mOnGoDb', 'table "Products"', 'collection "products"']
    ])('normalizes %s source and %s target technologies', async (source, target, sourceConfig, targetConfig) => {
        const result = await validate(`
            bridge NormalizedConnectorBridge {
                source ${source} { ${sourceConfig} }
                target ${target} { ${targetConfig} }
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);
    });

    it('accepts extra properties for registered connectors', async () => {
        const result = await validate(`
            bridge ExtraPropertyBridge {
                source MongoDB { collection "products" customOption "x" }
                target DynamoDB { table "Products" partitionKey "id" customOption "x" }
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);
    });

    it.each(['source', 'target'] as const)('accepts a dynamic technology property in %s', async connector => {
        const result = await validate(`
            bridge DynamicPropertyBridge {
                source MongoDB {
                    collection "products"
                    ${connector === 'source' ? 'technology "legacy"' : ''}
                }
                target DynamoDB {
                    table "Products"
                    partitionKey "id"
                    ${connector === 'target' ? 'technology "legacy"' : ''}
                }
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);
    });

    it('reports an error when a bridge has no mappings', async () => {
        const result = await validate(`
            bridge EmptyBridge {
                source MongoDB { collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                mapping {}
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                message: emptyMappingMessage,
                severity: DiagnosticSeverity.Error
            })
        ]));
    });

    it('does not report an empty mapping error when a bridge has mappings', async () => {
        const result = await validate(`
            bridge ValidBridge {
                source MongoDB { collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                mapping {
                    name -> name
                }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).not.toEqual(expect.arrayContaining([
            expect.objectContaining({
                message: emptyMappingMessage,
                severity: DiagnosticSeverity.Error
            })
        ]));
    });
});
