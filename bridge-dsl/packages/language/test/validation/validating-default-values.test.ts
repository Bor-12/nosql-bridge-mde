import type { BridgeModel } from '../../src/generated/ast.js';
import type { ConnectorDescriptor } from '../../src/connector-descriptors/connector-descriptor.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, validationHelper } from 'langium/test';
import { DiagnosticSeverity } from 'vscode-languageserver-types';
import { afterEach, describe, expect, it, vi } from 'vitest';

const scalarTypes = ['string', 'integer', 'decimal', 'boolean'] as const;

const mongoDescriptor: ConnectorDescriptor = {
    source: {
        supportedTypes: [...scalarTypes, 'object'],
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
        supportedTypes: scalarTypes,
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

async function createIsolatedValidate() {
    vi.resetModules();
    vi.doMock('../../src/connector-descriptors/connector-registry.js', () => ({
        getConnectorDescriptor: (technology: string): ConnectorDescriptor | undefined => {
            switch (technology.toLowerCase()) {
                case 'mongodb':
                    return mongoDescriptor;
                case 'stringonlystore':
                    return {
                        source: { ...mongoDescriptor.source, properties: [], supportedTypes: ['string'] },
                        target: { ...mongoDescriptor.target, properties: [], supportedTypes: ['string'] }
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

function bridge(mapping: string, options: {
    source?: string;
    target?: string;
    sourceTypes?: string;
    nulls?: string;
} = {}): string {
    return `
        bridge DefaultValidationBridge {
            source ${options.source ?? 'MongoDB'} { database "shop" collection "products" }
            target ${options.target ?? 'MongoDB'} { database "target" collection "products" }
            ${options.sourceTypes ? `sourceTypes { ${options.sourceTypes} }` : ''}
            ${options.nulls ?? ''}
            mapping { ${mapping} }
        }
    `;
}

async function check(input: string) {
    const { services, validate } = await createIsolatedValidate();
    const result = await validate(input);
    expect(result.document.parseResult.lexerErrors).toEqual([]);
    expect(result.document.parseResult.parserErrors).toEqual([]);
    expect(result.diagnostics.every(diagnostic => diagnostic.severity === DiagnosticSeverity.Error)).toBe(true);
    await clearDocuments(services.shared);
    vi.doUnmock('../../src/connector-descriptors/connector-registry.js');
    vi.resetModules();
    return result;
}

afterEach(() => {
    vi.doUnmock('../../src/connector-descriptors/connector-registry.js');
    vi.resetModules();
});

describe('mapping default value validation', () => {
    it.each([
        'status -> status default "ACTIVE"',
        'stock -> stock default 0',
        'price -> price default 12.5',
        'active -> active default false'
    ])('accepts scalar default without transformation: %s', async mapping => {
        const result = await check(bridge(mapping));

        expect(result.diagnostics).toEqual([]);
    });

    it('accepts a default compatible with transformation input and target output', async () => {
        const result = await check(bridge('price -> amount using toDecimal default "0"', {
            sourceTypes: 'price string'
        }));

        expect(result.diagnostics).toEqual([]);
    });

    it('rejects a default incompatible with transformation input at the default literal', async () => {
        const result = await check(bridge('price -> amount using toDecimal default true', {
            sourceTypes: 'price string'
        }));

        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: "Transformation 'toDecimal' cannot accept default type 'boolean' for target field 'amount'.",
                severity: DiagnosticSeverity.Error
            })
        ]);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe('true');
    });

    it('rejects a default type unsupported by the target when there is no transformation', async () => {
        const result = await check(bridge('value -> value default true', {
            source: 'StringOnlyStore',
            target: 'StringOnlyStore'
        }));

        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: "Target technology 'StringOnlyStore' does not support default type 'boolean' for field 'value'.",
                severity: DiagnosticSeverity.Error
            })
        ]);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe('true');
    });

    it('does not cascade default validation when the transformation is unknown', async () => {
        const result = await check(bridge('price -> amount using UnknownTransformation default true', {
            source: 'StringOnlyStore',
            target: 'StringOnlyStore',
            sourceTypes: 'price string'
        }));

        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: "Unknown transformation 'UnknownTransformation'.",
                severity: DiagnosticSeverity.Error
            })
        ]);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe('UnknownTransformation');
    });

    it('reports source type and default transformation input errors independently', async () => {
        const result = await check(bridge('price -> amount using toDecimal default true', {
            sourceTypes: 'price boolean'
        }));

        expect(result.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                message: "Transformation 'toDecimal' cannot accept type 'boolean' declared for source field 'price'.",
                severity: DiagnosticSeverity.Error
            }),
            expect.objectContaining({
                message: "Transformation 'toDecimal' cannot accept default type 'boolean' for target field 'amount'.",
                severity: DiagnosticSeverity.Error
            })
        ]));
        expect(result.diagnostics).toHaveLength(2);
        expect(result.diagnostics.map(diagnostic => result.document.textDocument.getText(diagnostic.range)).sort())
            .toEqual(['boolean', 'true']);
    });

    it('does not validate the default against source supported types', async () => {
        const result = await check(bridge('value -> value default true', {
            source: 'StringOnlyStore'
        }));

        expect(result.diagnostics).toEqual([]);
    });

    it.each([
        {
            name: 'missing sourceTypes and incompatible default',
            sourceTypes: undefined,
            literal: 'true',
            messages: [
                "Source field 'price' used with transformation 'toDecimal' must declare at least one type.",
                "Transformation 'toDecimal' cannot accept default type 'boolean' for target field 'amount'."
            ],
            ranges: ['price', 'true']
        },
        {
            name: 'missing sourceTypes and compatible default',
            sourceTypes: undefined,
            literal: '"0"',
            messages: [
                "Source field 'price' used with transformation 'toDecimal' must declare at least one type.",
                "Target technology 'StringOnlyStore' does not support type 'decimal' produced by transformation 'toDecimal' for field 'amount'."
            ],
            ranges: ['price', 'toDecimal']
        },
        {
            name: 'compatible source and default produce only one output error',
            sourceTypes: 'price string',
            literal: '"0"',
            messages: [
                "Target technology 'StringOnlyStore' does not support type 'decimal' produced by transformation 'toDecimal' for field 'amount'."
            ],
            ranges: ['toDecimal']
        },
        {
            name: 'incompatible source and default suppress output errors',
            sourceTypes: 'price object',
            literal: 'true',
            messages: [
                "Transformation 'toDecimal' cannot accept type 'object' declared for source field 'price'.",
                "Transformation 'toDecimal' cannot accept default type 'boolean' for target field 'amount'."
            ],
            ranges: ['object', 'true']
        },
        {
            name: 'compatible source still permits output validation with incompatible default',
            sourceTypes: 'price string',
            literal: 'true',
            messages: [
                "Transformation 'toDecimal' cannot accept default type 'boolean' for target field 'amount'.",
                "Target technology 'StringOnlyStore' does not support type 'decimal' produced by transformation 'toDecimal' for field 'amount'."
            ],
            ranges: ['true', 'toDecimal']
        },
        {
            name: 'compatible default still permits output validation with incompatible source',
            sourceTypes: 'price object',
            literal: '"0"',
            messages: [
                "Transformation 'toDecimal' cannot accept type 'object' declared for source field 'price'.",
                "Target technology 'StringOnlyStore' does not support type 'decimal' produced by transformation 'toDecimal' for field 'amount'."
            ],
            ranges: ['object', 'toDecimal']
        }
    ])('$name against an incompatible target', async ({ sourceTypes, literal, messages, ranges }) => {
        const result = await check(bridge(`price -> amount using toDecimal default ${literal}`, {
            sourceTypes,
            target: 'StringOnlyStore'
        }));

        expect(result.diagnostics.map(diagnostic => diagnostic.message).sort()).toEqual([...messages].sort());
        expect(result.diagnostics.map(diagnostic => result.document.textDocument.getText(diagnostic.range)).sort())
            .toEqual([...ranges].sort());
    });

    it('does not cascade an unknown transformation without sourceTypes against an incompatible target', async () => {
        const result = await check(bridge('price -> amount using UnknownTransformation default true', {
            target: 'StringOnlyStore'
        }));

        expect(result.diagnostics.map(diagnostic => diagnostic.message)).toEqual([
            "Unknown transformation 'UnknownTransformation'."
        ]);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe('UnknownTransformation');
    });

    it('keeps sourceTypes required for real source values when a transformation is used with a default', async () => {
        const result = await check(bridge('price -> amount using toDecimal default "0"'));

        expect(result.diagnostics).toEqual([
            expect.objectContaining({
                message: "Source field 'price' used with transformation 'toDecimal' must declare at least one type.",
                severity: DiagnosticSeverity.Error
            })
        ]);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe('price');
    });

    it.each(['nulls preserve', 'nulls omit'])('accepts %s with a mapping default', async nulls => {
        const result = await check(bridge('status -> status default "ACTIVE"', { nulls }));

        expect(result.diagnostics).toEqual([]);
    });
});
