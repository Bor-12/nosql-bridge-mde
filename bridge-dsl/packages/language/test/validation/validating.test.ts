import type { BridgeModel } from '../../src/generated/ast.js';
import { createBridgeServices } from '../../src/bridge-module.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, validationHelper } from 'langium/test';
import { DiagnosticSeverity } from 'vscode-languageserver-types';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';

const emptyMappingMessage = 'A bridge must contain at least one mapping.';
const duplicateTargetMappingMessageFragment = 'is mapped more than once';

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
            name: 'rejects a duplicate simple target',
            mappings: `
                _id -> id
                legacyId -> id
            `,
            duplicateTargetPaths: ['id'],
            duplicateIndexes: [1]
        },
        {
            name: 'rejects a duplicate nested target',
            mappings: `
                name -> product.name
                legacyName -> product.name
                _id -> id
            `,
            duplicateTargetPaths: ['product.name'],
            duplicateIndexes: [1]
        }
    ])('$name', async ({ mappings, duplicateTargetPaths, duplicateIndexes }) => {
        const result = await validate(`
            bridge DuplicateTargetBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                mapping {
                    ${mappings}
                }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual(duplicateIndexes.map((mappingIndex, diagnosticIndex) => {
            const target = result.document.parseResult.value.mapping.mappings[mappingIndex].target;
            const targetPath = duplicateTargetPaths[diagnosticIndex];
            const offset = target.$cstNode!.offset;
            return expect.objectContaining({
                message: `Target field '${targetPath}' is mapped more than once.`,
                severity: DiagnosticSeverity.Error,
                range: {
                    start: result.document.textDocument.positionAt(offset),
                    end: result.document.textDocument.positionAt(offset + targetPath.length)
                }
            });
        }));
    });

    it.each([
        {
            name: 'rejects a parent target before its child without classifying it as a duplicate',
            mappings: 'name -> product\nprice -> product.price',
            conflictingTargetPath: 'product.price'
        },
        {
            name: 'rejects a child target before its parent without classifying it as a duplicate',
            mappings: 'price -> product.price\nname -> product',
            conflictingTargetPath: 'product'
        }
    ])('$name', async ({ mappings, conflictingTargetPath }) => {
        const result = await validate(`
            bridge ConflictingTargetBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                mapping {
                    ${mappings}
                    _id -> id
                }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        const target = result.document.parseResult.value.mapping.mappings[1].target;
        const offset = target.$cstNode!.offset;
        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: "Target mappings 'product' and 'product.price' conflict because one path contains the other.",
                severity: DiagnosticSeverity.Error,
                range: {
                    start: result.document.textDocument.positionAt(offset),
                    end: result.document.textDocument.positionAt(offset + conflictingTargetPath.length)
                }
            })
        ]);
        expect(result.diagnostics).not.toEqual(expect.arrayContaining([
            expect.objectContaining({
                message: expect.stringContaining(duplicateTargetMappingMessageFragment)
            })
        ]));
    });

    it.each([
        {
            name: 'accepts sibling target paths',
            mappings: 'name -> product.name\nprice -> product.price'
        },
        {
            name: 'accepts textual prefixes that are not complete path segments',
            mappings: 'sourceA -> product\nsourceB -> productCode'
        }
    ])('$name', async ({ mappings }) => {
        const result = await validate(`
            bridge NonConflictingTargetBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                mapping {
                    ${mappings}
                    _id -> id
                }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);
    });

    it.each([
        {
            name: 'rejects a duplicate source property',
            source: 'source MongoDB { database "shop" collection "products" collection "products-v2" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            role: 'source',
            duplicateIndexes: [2]
        },
        {
            name: 'rejects a duplicate target property',
            source: 'source MongoDB { database "shop" collection "products" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" partitionKey "otherId" }',
            role: 'target',
            duplicateIndexes: [2]
        },
        {
            name: 'reports each occurrence after the first',
            source: 'source MongoDB { database "shop" collection "a" collection "b" collection "c" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            role: 'source',
            duplicateIndexes: [2, 3]
        },
        {
            name: 'accepts distinct extra properties within each connector',
            source: 'source MongoDB { database "shop" collection "products" customOption "x" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" customOption "x" }',
            role: 'source',
            duplicateIndexes: []
        },
        {
            name: 'treats property names as case-sensitive',
            source: 'source MongoDB { database "shop" collection "products" Collection "other" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            role: 'source',
            duplicateIndexes: []
        }
    ] as const)('$name', async ({ source, target, role, duplicateIndexes }) => {
        const result = await validate(`
            bridge DuplicatePropertyBridge {
                ${source}
                ${target}
                mapping { name -> name _id -> id }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        const connector = result.document.parseResult.value[role];
        expect(result.diagnostics).toEqual(duplicateIndexes.map(index => {
            const property = connector.properties[index];
            const offset = property.$cstNode!.offset;
            return expect.objectContaining({
                message: `Property '${property.name}' is declared more than once in ${connector.technology} ${role}.`,
                severity: DiagnosticSeverity.Error,
                range: {
                    start: result.document.textDocument.positionAt(offset),
                    end: result.document.textDocument.positionAt(offset + property.name.length)
                }
            });
        }));
    });

    it.each([
        {
            name: 'requires database for MongoDB source',
            source: 'source MongoDB { collection "products" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            messages: ["Missing required property 'database' for MongoDB source."]
        },
        {
            name: 'requires collection for MongoDB source',
            source: 'source MongoDB { database "shop" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            messages: ["Missing required property 'collection' for MongoDB source."]
        },
        {
            name: 'reports every missing property for MongoDB source',
            source: 'source MongoDB {}',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            messages: [
                "Missing required property 'database' for MongoDB source.",
                "Missing required property 'collection' for MongoDB source."
            ]
        },
        {
            name: 'accepts database and collection for MongoDB source',
            source: 'source MongoDB { database "shop" collection "products" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            messages: []
        },
        {
            name: 'requires partitionKey for DynamoDB target',
            source: 'source MongoDB { database "shop" collection "products" }',
            target: 'target DynamoDB { table "Products" }',
            messages: ["Missing required property 'partitionKey' for DynamoDB target."]
        },
        {
            name: 'accepts table and partitionKey for DynamoDB target',
            source: 'source MongoDB { database "shop" collection "products" }',
            target: 'target DynamoDB { table "Products" partitionKey "id" }',
            messages: []
        },
        {
            name: 'requires table for DynamoDB source',
            source: 'source DynamoDB {}',
            target: 'target MongoDB { database "shop" collection "products" }',
            messages: ["Missing required property 'table' for DynamoDB source."]
        },
        {
            name: 'requires database for MongoDB target',
            source: 'source DynamoDB { table "Products" }',
            target: 'target MongoDB { collection "products" }',
            messages: ["Missing required property 'database' for MongoDB target."]
        },
        {
            name: 'requires collection for MongoDB target',
            source: 'source DynamoDB { table "Products" }',
            target: 'target MongoDB { database "shop" }',
            messages: ["Missing required property 'collection' for MongoDB target."]
        },
        {
            name: 'reports every missing property for MongoDB target',
            source: 'source DynamoDB { table "Products" }',
            target: 'target MongoDB {}',
            messages: [
                "Missing required property 'database' for MongoDB target.",
                "Missing required property 'collection' for MongoDB target."
            ]
        },
        {
            name: 'accepts database and collection for MongoDB target',
            source: 'source DynamoDB { table "Products" }',
            target: 'target MongoDB { database "shop" collection "products" }',
            messages: []
        },
        {
            name: 'reports every missing property for DynamoDB target',
            source: 'source MongoDB { database "shop" collection "products" }',
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
                mapping { name -> name _id -> id }
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
                    : 'source MongoDB { database "shop" collection "products" }'}
                ${connector === 'target'
                    ? 'target UnknownDB { foo "bar" }'
                    : 'target DynamoDB { table "Products" partitionKey "id" }'}
                mapping { name -> name _id -> id }
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
        {
            name: 'accepts explicit nulls preserve when target supports null values',
            declaration: 'nulls preserve'
        },
        {
            name: 'accepts nulls omit when target supports null values',
            declaration: 'nulls omit'
        }
    ])('$name', async ({ declaration }) => {
        const result = await validate(`
            bridge NullPolicyBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                ${declaration}
                mapping { name -> name _id -> id }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);
    });

    it.each([
        ['mOnGoDb', 'DyNaMoDb', 'database "shop" collection "products"', 'table "Products" partitionKey "id"'],
        ['DyNaMoDb', 'mOnGoDb', 'table "Products"', 'database "shop" collection "products"']
    ])('normalizes %s source and %s target technologies', async (source, target, sourceConfig, targetConfig) => {
        const result = await validate(`
            bridge NormalizedConnectorBridge {
                source ${source} { ${sourceConfig} }
                target ${target} { ${targetConfig} }
                mapping { name -> name _id -> id }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);
    });

    it('accepts extra properties for registered connectors', async () => {
        const result = await validate(`
            bridge ExtraPropertyBridge {
                source MongoDB { database "shop" collection "products" customOption "x" }
                target DynamoDB { table "Products" partitionKey "id" customOption "x" }
                mapping { name -> name _id -> id }
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
                    database "shop"
                    collection "products"
                    ${connector === 'source' ? 'technology "legacy"' : ''}
                }
                target DynamoDB {
                    table "Products"
                    partitionKey "id"
                    ${connector === 'target' ? 'technology "legacy"' : ''}
                }
                mapping { name -> name _id -> id }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);
    });

    it('reports an error when a bridge with unmapped drop has no mappings', async () => {
        const result = await validate(`
            bridge EmptyBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                unmapped drop
                mapping {}
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: emptyMappingMessage,
                severity: DiagnosticSeverity.Error
            })
        ]);
    });

    it.each(['nulls preserve', 'nulls omit'] as const)('keeps empty mappings invalid with %s', async nullDeclaration => {
        const result = await validate(`
            bridge EmptyBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                ${nullDeclaration}
                mapping {}
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: emptyMappingMessage,
                severity: DiagnosticSeverity.Error
            })
        ]);
    });

    it('does not report an empty mapping error when a bridge has mappings', async () => {
        const result = await validate(`
            bridge ValidBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { table "Products" partitionKey "id" }
                mapping {
                    name -> name
                    _id -> id
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
