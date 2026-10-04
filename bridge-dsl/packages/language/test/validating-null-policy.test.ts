import type { BridgeModel } from '../src/generated/ast.js';
import type { ConnectorDescriptor } from '../src/connector-descriptors/connector-descriptor.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, validationHelper } from 'langium/test';
import { DiagnosticSeverity } from 'vscode-languageserver-types';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mongoDescriptor: ConnectorDescriptor = {
    source: {
        requiredProperties: ['database', 'collection'],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        requiredProperties: ['database', 'collection'],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    }
};

const noNullStoreDescriptor: ConnectorDescriptor = {
    source: {
        requiredProperties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        requiredProperties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: false,
            nestedFields: true
        }
    }
};

async function createIsolatedValidate() {
    vi.resetModules();
    vi.doMock('../src/connector-descriptors/connector-registry.js', () => ({
        getConnectorDescriptor: (technology: string): ConnectorDescriptor | undefined => {
            switch (technology.toLowerCase()) {
                case 'mongodb':
                    return mongoDescriptor;
                case 'nonullstore':
                    return noNullStoreDescriptor;
                default:
                    return undefined;
            }
        }
    }));

    const { createBridgeServices } = await import('../src/bridge-module.js');
    const services = createBridgeServices(EmptyFileSystem);
    return {
        services,
        validate: validationHelper<BridgeModel>(services.Bridge)
    };
}

describe('Bridge DSL null policy validation with mocked connector capabilities', () => {
    beforeEach(() => {
        vi.resetModules();
    });

    afterEach(() => {
        vi.doUnmock('../src/connector-descriptors/connector-registry.js');
        vi.resetModules();
    });

    it.each([
        {
            name: 'rejects explicit nulls preserve when the target does not support null values',
            declaration: 'nulls preserve',
            expectedRangeText: 'preserve'
        },
        {
            name: 'rejects omitted nulls when the target does not support null values',
            declaration: '',
            expectedRangeText: 'NoNullStore'
        }
    ])('$name', async ({ declaration, expectedRangeText }) => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge NullPolicyBridge {
                source MongoDB { database "shop" collection "products" }
                target NoNullStore {}
                ${declaration}
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        const diagnostic = expect.objectContaining({
            message: "Target technology 'NoNullStore' does not support null values required by nulls preserve.",
            severity: DiagnosticSeverity.Error
        });
        expect(result.diagnostics).toEqual([diagnostic]);
        const [actualDiagnostic] = result.diagnostics;
        const rangeText = result.document.textDocument.getText(actualDiagnostic.range);
        expect(rangeText).toBe(expectedRangeText);

        await clearDocuments(services.shared);
    });

    it('accepts nulls omit when the target does not support null values', async () => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge NullPolicyBridge {
                source MongoDB { database "shop" collection "products" }
                target NoNullStore {}
                nulls omit
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);

        await clearDocuments(services.shared);
    });

    it('does not duplicate diagnostics for an unknown target connector', async () => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge UnknownConnectorBridge {
                source MongoDB { database "shop" collection "products" }
                target UnknownDB {}
                nulls preserve
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

        await clearDocuments(services.shared);
    });
});
