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

const fixedSchemaStoreDescriptor: ConnectorDescriptor = {
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
            dynamicFields: false,
            nullValues: true,
            nestedFields: true
        }
    }
};

const flatSourceStoreDescriptor: ConnectorDescriptor = {
    source: {
        requiredProperties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: false
        }
    },
    target: {
        requiredProperties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    }
};

const flatTargetStoreDescriptor: ConnectorDescriptor = {
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
            nullValues: true,
            nestedFields: false
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
                case 'fixedschemastore':
                    return fixedSchemaStoreDescriptor;
                case 'flatsourcestore':
                    return flatSourceStoreDescriptor;
                case 'flattargetstore':
                    return flatTargetStoreDescriptor;
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

describe('Bridge DSL validation with mocked connector capabilities', () => {
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

    it.each(['', 'unmapped preserve'])('accepts nulls omit with %s when the target does not support null values', async unmappedDeclaration => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge NullPolicyBridge {
                source MongoDB { database "shop" collection "products" }
                target NoNullStore {}
                ${unmappedDeclaration}
                nulls omit
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);

        await clearDocuments(services.shared);
    });

    it.each(['nulls preserve', 'unmapped preserve'])('does not duplicate diagnostics for an unknown target connector with %s', async declaration => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge UnknownConnectorBridge {
                source MongoDB { database "shop" collection "products" }
                target UnknownDB {}
                ${declaration}
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

    it('rejects unmapped preserve when the target does not support dynamic fields', async () => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge UnmappedPolicyBridge {
                source MongoDB { database "shop" collection "products" }
                target FixedSchemaStore {}
                unmapped preserve
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: "Target technology 'FixedSchemaStore' does not support dynamic fields required by unmapped preserve.",
                severity: DiagnosticSeverity.Error
            })
        ]);
        const [diagnostic] = result.diagnostics;
        expect(result.document.textDocument.getText(diagnostic.range)).toBe('preserve');

        await clearDocuments(services.shared);
    });

    it.each(['unmapped drop', ''])('accepts %s when the target does not support dynamic fields', async declaration => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge UnmappedPolicyBridge {
                source MongoDB { database "shop" collection "products" }
                target FixedSchemaStore {}
                ${declaration}
                nulls preserve
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);

        await clearDocuments(services.shared);
    });

    it('accepts unmapped preserve when the target supports dynamic fields', async () => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge UnmappedPolicyBridge {
                source MongoDB { database "shop" collection "products" }
                target MongoDB { database "target" collection "products" }
                unmapped preserve
                mapping { name -> name }
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([]);

        await clearDocuments(services.shared);
    });

    it.each([
        {
            role: 'source',
            source: 'FlatSourceStore {}',
            target: 'MongoDB { database "target" collection "products" }',
            mapping: 'category.name -> category',
            message: "Source technology 'FlatSourceStore' does not support nested fields required by source path 'category.name'.",
            path: 'category.name'
        },
        {
            role: 'target',
            source: 'MongoDB { database "shop" collection "products" }',
            target: 'FlatTargetStore {}',
            mapping: 'name -> product.name',
            message: "Target technology 'FlatTargetStore' does not support nested fields required by target path 'product.name'.",
            path: 'product.name'
        }
    ])('rejects a nested $role path when that role does not support nested fields', async ({ source, target, mapping, message, path }) => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge NestedPathBridge {
                source ${source}
                target ${target}
                mapping { ${mapping} }
            }
        `);

        expect(result.diagnostics).toEqual([
            expect.objectContaining({ message, severity: DiagnosticSeverity.Error })
        ]);
        const [diagnostic] = result.diagnostics;
        expect(result.document.textDocument.getText(diagnostic.range)).toBe(path);

        await clearDocuments(services.shared);
    });

    it.each([
        {
            name: 'simple source path with nestedFields=false',
            source: 'FlatSourceStore {}',
            target: 'MongoDB { database "target" collection "products" }',
            mapping: 'name -> name'
        },
        {
            name: 'simple target path with nestedFields=false',
            source: 'MongoDB { database "shop" collection "products" }',
            target: 'FlatTargetStore {}',
            mapping: 'name -> name'
        },
        {
            name: 'nested source and target paths with capable connectors',
            source: 'MongoDB { database "shop" collection "products" }',
            target: 'MongoDB { database "target" collection "products" }',
            mapping: 'product.name -> details.name'
        },
        {
            name: 'nested paths using the capable roles of asymmetric connectors',
            source: 'FlatTargetStore {}',
            target: 'FlatSourceStore {}',
            mapping: 'product.name -> details.name'
        }
    ])('accepts $name', async ({ source, target, mapping }) => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge NestedPathBridge {
                source ${source}
                target ${target}
                mapping { ${mapping} }
            }
        `);

        expect(result.diagnostics).toEqual([]);

        await clearDocuments(services.shared);
    });

    it.each([
        {
            role: 'source',
            source: 'UnknownDB {}',
            target: 'MongoDB { database "target" collection "products" }'
        },
        {
            role: 'target',
            source: 'MongoDB { database "shop" collection "products" }',
            target: 'UnknownDB {}'
        }
    ])('does not add nested diagnostics for an unknown $role connector', async ({ source, target }) => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge NestedPathBridge {
                source ${source}
                target ${target}
                mapping { product.name -> details.name }
            }
        `);

        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: "Unknown connector technology 'UnknownDB'.",
                severity: DiagnosticSeverity.Error
            })
        ]);

        await clearDocuments(services.shared);
    });

    it('reports both unsupported roles independently for nested source and target paths', async () => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge NestedPathBridge {
                source FlatSourceStore {}
                target FlatTargetStore {}
                mapping { product.name -> details.name }
            }
        `);

        expect(result.diagnostics).toHaveLength(2);
        expect(result.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                message: "Source technology 'FlatSourceStore' does not support nested fields required by source path 'product.name'.",
                severity: DiagnosticSeverity.Error
            }),
            expect.objectContaining({
                message: "Target technology 'FlatTargetStore' does not support nested fields required by target path 'details.name'.",
                severity: DiagnosticSeverity.Error
            })
        ]));
        expect(result.diagnostics.map(diagnostic => result.document.textDocument.getText(diagnostic.range)).sort())
            .toEqual(['details.name', 'product.name']);

        await clearDocuments(services.shared);
    });

    it('keeps empty mappings invalid with unmapped preserve and a capable target', async () => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`
            bridge UnmappedPolicyBridge {
                source MongoDB { database "shop" collection "products" }
                target MongoDB { database "target" collection "products" }
                unmapped preserve
                mapping {}
            }
        `);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: 'A bridge must contain at least one mapping.',
                severity: DiagnosticSeverity.Error
            })
        ]);

        await clearDocuments(services.shared);
    });
});
