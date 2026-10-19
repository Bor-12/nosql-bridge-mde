import type { BridgeModel } from '../../src/generated/ast.js';
import type { ConnectorDescriptor } from '../../src/connector-descriptors/connector-descriptor.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, validationHelper } from 'langium/test';
import { DiagnosticSeverity } from 'vscode-languageserver-types';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const allTestTypes = ['string', 'integer', 'decimal', 'boolean', 'binary', 'object', 'array', 'timestamp'] as const;

const mongoDescriptor: ConnectorDescriptor = {
    source: {
        supportedTypes: allTestTypes,
        properties: [
            { name: 'database', required: true, semantic: 'value' },
            { name: 'collection', required: true, semantic: 'value' }
        ],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        supportedTypes: allTestTypes,
        properties: [
            { name: 'database', required: true, semantic: 'value' },
            { name: 'collection', required: true, semantic: 'value' }
        ],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    }
};

const noNullStoreDescriptor: ConnectorDescriptor = {
    source: {
        supportedTypes: allTestTypes,
        properties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        supportedTypes: allTestTypes,
        properties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: false,
            nestedFields: true
        }
    }
};

const fixedSchemaStoreDescriptor: ConnectorDescriptor = {
    source: {
        supportedTypes: allTestTypes,
        properties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        supportedTypes: allTestTypes,
        properties: [],
        capabilities: {
            dynamicFields: false,
            nullValues: true,
            nestedFields: true
        }
    }
};

const flatSourceStoreDescriptor: ConnectorDescriptor = {
    source: {
        supportedTypes: allTestTypes,
        properties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: false
        }
    },
    target: {
        supportedTypes: allTestTypes,
        properties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    }
};

const flatTargetStoreDescriptor: ConnectorDescriptor = {
    source: {
        supportedTypes: allTestTypes,
        properties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        supportedTypes: allTestTypes,
        properties: [],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: false
        }
    }
};

async function createIsolatedValidate() {
    vi.resetModules();
    vi.doMock('../../src/connector-descriptors/connector-registry.js', () => ({
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
                case 'stringonlystore':
                    return {
                        source: mongoDescriptor.source,
                        target: { ...mongoDescriptor.target, properties: [], supportedTypes: ['string'] }
                    };
                case 'customtypestore':
                    return {
                        source: { ...mongoDescriptor.source, properties: [], supportedTypes: ['string', 'geoPoint'] },
                        target: { ...mongoDescriptor.target, properties: [], supportedTypes: ['string', 'geoPoint'] }
                    };
                default:
                    return undefined;
            }
        }
    }));

    const { createBridgeServices } = await import('../../src/bridge-module.js');
    const services = createBridgeServices(EmptyFileSystem);
    return {
        services,
        validate: validationHelper<BridgeModel>(services.Bridge)
    };
}

describe('Bridge DSL validation with mocked connector capabilities', () => {
    it.each([
        { target: 'CustomTypeStore', using: '', message: undefined, range: undefined },
        {
            target: 'CustomTypeStore', using: 'using toDecimal',
            message: "Transformation 'toDecimal' cannot accept type 'geoPoint' declared for source field 'location'.",
            range: 'geoPoint'
        },
        {
            target: 'StringOnlyStore', using: '',
            message: "Target technology 'StringOnlyStore' does not support declared type 'geoPoint' mapped from source field 'location'.",
            range: 'location'
        }
    ])('validates custom geoPoint with target $target and $using', async ({ target, using, message, range }) => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`bridge CustomTypes {
            source CustomTypeStore {}
            target ${target} {}
            sourceTypes { location geoPoint }
            mapping { location -> location ${using} }
        }`);
        expect(result.document.parseResult.lexerErrors).toEqual([]);
        expect(result.document.parseResult.parserErrors).toEqual([]);
        expect(result.diagnostics).toEqual(message ? [expect.objectContaining({
            message, severity: DiagnosticSeverity.Error
        })] : []);
        if (range) {
            expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe(range);
        }
        await clearDocuments(services.shared);
    });

    it('marks the second duplicate custom type supported by the connector', async () => {
        const { services, validate } = await createIsolatedValidate();
        const input = `bridge CustomTypes {
            source CustomTypeStore {}
            target CustomTypeStore {}
            sourceTypes { location geoPoint | geoPoint }
            mapping { location -> location }
        }`;
        const result = await validate(input);
        expect(result.diagnostics).toEqual([expect.objectContaining({
            message: "Type 'geoPoint' is declared more than once for source field 'location'.",
            severity: DiagnosticSeverity.Error
        })]);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe('geoPoint');
        expect(result.document.textDocument.offsetAt(result.diagnostics[0].range.start)).toBe(input.lastIndexOf('geoPoint'));
        await clearDocuments(services.shared);
    });

    it('rejects a transformation output using target metadata and marks the transformation', async () => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`bridge Types {
            source MongoDB { database "shop" collection "products" }
            target StringOnlyStore {}
            sourceTypes { price string }
            mapping { price -> amount using toDecimal }
        }`);
        expect(result.diagnostics).toEqual([expect.objectContaining({
            message: "Target technology 'StringOnlyStore' does not support type 'decimal' produced by transformation 'toDecimal' for field 'amount'.",
            severity: DiagnosticSeverity.Error
        })]);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe('toDecimal');
        await clearDocuments(services.shared);
    });

    it.each([
        { types: '', transformation: 'toDecimal', message: "Source field 'price' used with transformation 'toDecimal' must declare at least one type." },
        { types: 'sourceTypes { price object }', transformation: 'toDecimal', message: "Transformation 'toDecimal' cannot accept type 'object' declared for source field 'price'." },
        { types: 'sourceTypes { price timestamp }', transformation: 'UnknownTransformation', message: "Unknown transformation 'UnknownTransformation'." }
    ])('suppresses output errors when input validation fails: $message', async ({ types, transformation, message }) => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`bridge Types {
            source MongoDB { database "shop" collection "products" }
            target StringOnlyStore {}
            ${types}
            mapping { price -> amount using ${transformation} }
        }`);
        expect(result.diagnostics.map(d => d.message)).toEqual([message]);
        await clearDocuments(services.shared);
    });

    it('deduplicates incompatible inputs and suppresses an unsupported transformation output', async () => {
        const { services, validate } = await createIsolatedValidate();
        const input = `bridge Types {
            source MongoDB { database "shop" collection "products" }
            target StringOnlyStore {}
            sourceTypes { value object | object }
            mapping { value -> amount using toDecimal }
        }`;
        const result = await validate(input);
        expect(result.document.parseResult.lexerErrors).toEqual([]);
        expect(result.document.parseResult.parserErrors).toEqual([]);
        expect(result.diagnostics.map(d => d.message).sort()).toEqual([
            "Transformation 'toDecimal' cannot accept type 'object' declared for source field 'value'.",
            "Type 'object' is declared more than once for source field 'value'."
        ]);
        expect(result.diagnostics.every(d => d.severity === DiagnosticSeverity.Error)).toBe(true);
        for (const diagnostic of result.diagnostics) {
            expect(result.document.textDocument.getText(diagnostic.range)).toBe('object');
            const expectedOffset = diagnostic.message === "Type 'object' is declared more than once for source field 'value'."
                ? input.lastIndexOf('object') : input.indexOf('object');
            expect(result.document.textDocument.offsetAt(diagnostic.range.start)).toBe(expectedOffset);
        }
        await clearDocuments(services.shared);
    });

    it('reports every unsupported passthrough type from target metadata', async () => {
        const { services, validate } = await createIsolatedValidate();
        const result = await validate(`bridge Types {
            source MongoDB { database "shop" collection "products" }
            target StringOnlyStore {}
            sourceTypes { price string | decimal | timestamp }
            mapping { price -> amount }
        }`);
        expect(result.diagnostics.map(d => d.message).sort()).toEqual([
            "Target technology 'StringOnlyStore' does not support declared type 'decimal' mapped from source field 'price'.",
            "Target technology 'StringOnlyStore' does not support declared type 'timestamp' mapped from source field 'price'."
        ]);
        expect(result.diagnostics.map(d => result.document.textDocument.getText(d.range))).toEqual(['amount', 'amount']);
        await clearDocuments(services.shared);
    });

    beforeEach(() => {
        vi.resetModules();
    });

    afterEach(() => {
        vi.doUnmock('../../src/connector-descriptors/connector-registry.js');
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
            message: "Target technology 'NoNullStore' cannot use 'nulls preserve' because it does not support null values.",
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
                message: "Target technology 'FixedSchemaStore' cannot use 'unmapped preserve' because it does not support dynamic fields.",
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
            message: "Nested source path 'category.name' is not supported by source technology 'FlatSourceStore'.",
            path: 'category.name'
        },
        {
            role: 'target',
            source: 'MongoDB { database "shop" collection "products" }',
            target: 'FlatTargetStore {}',
            mapping: 'name -> product.name',
            message: "Nested target path 'product.name' is not supported by target technology 'FlatTargetStore'.",
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
                message: "Nested source path 'product.name' is not supported by source technology 'FlatSourceStore'.",
                severity: DiagnosticSeverity.Error
            }),
            expect.objectContaining({
                message: "Nested target path 'details.name' is not supported by target technology 'FlatTargetStore'.",
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
