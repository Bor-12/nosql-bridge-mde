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
    it.each(['source', 'target'] as const)('accepts a dynamic technology property in %s', async connector => {
        const result = await validate(`
            bridge DynamicPropertyBridge {
                source MongoDB {
                    ${connector === 'source' ? 'technology "legacy"' : ''}
                }
                target DynamoDB {
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
                source MongoDB {}
                target DynamoDB {}
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
                source MongoDB {}
                target DynamoDB {}
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
