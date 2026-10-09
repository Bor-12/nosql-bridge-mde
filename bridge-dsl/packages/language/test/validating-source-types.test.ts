import type { BridgeModel } from '../src/generated/ast.js';
import { createBridgeServices } from '../src/bridge-module.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, validationHelper } from 'langium/test';
import { DiagnosticSeverity } from 'vscode-languageserver-types';
import { afterEach, describe, expect, it } from 'vitest';

const mongodbSupportedTypes = [
    'string',
    'integer',
    'decimal',
    'boolean',
    'binary',
    'object',
    'array',
    'timestamp'
] as const;

const dynamodbSupportedTypes = [
    'string',
    'integer',
    'decimal',
    'boolean',
    'binary',
    'object',
    'array'
] as const;

const services = createBridgeServices(EmptyFileSystem);
const validate = validationHelper<BridgeModel>(services.Bridge);
afterEach(async () => { await clearDocuments(services.shared); });

function bridge(types: string, mappings = 'value -> amount', source = 'MongoDB', target = 'DynamoDB'): string {
    return `bridge Types {
        source ${source} { database "shop" collection "products" table "Products" }
        target ${target} { database "shop" collection "products" table "Products" partitionKey "id" }
        ${types ? `sourceTypes { ${types} }` : ''}
        mapping { _id -> id ${mappings} }
    }`;
}

async function check(input: string, messages: string[], ranges?: string[]) {
    const result = await validate(input);
    expect(result.document.parseResult.lexerErrors).toEqual([]);
    expect(result.document.parseResult.parserErrors).toEqual([]);
    expect(result.diagnostics.map(d => d.message).sort()).toEqual([...messages].sort());
    expect(result.diagnostics.every(d => d.severity === DiagnosticSeverity.Error)).toBe(true);
    if (ranges) {
        expect(result.diagnostics.map(d => result.document.textDocument.getText(d.range)).sort()).toEqual([...ranges].sort());
    }
    return result;
}

describe('source type declarations', () => {
    it('marks the second duplicate full path declaration', async () => {
        const input = bridge('product.price string product.price integer');
        const result = await check(input, ["Source field 'product.price' has more than one type declaration."], ['product.price']);
        expect(result.document.textDocument.offsetAt(result.diagnostics[0].range.start)).toBe(input.indexOf('product.price integer'));
    });
    it('marks the second duplicate type token', async () => {
        const input = bridge('value string | decimal | string');
        const result = await check(input, ["Type 'string' is declared more than once for source field 'value'."], ['string']);
        expect(result.document.textDocument.offsetAt(result.diagnostics[0].range.start)).toBe(input.lastIndexOf('string'));
    });
    it.each(['', 'unused timestamp', 'product object product.price decimal'])('allows partial, unused and parent/child declarations: %s', async types => {
        await check(bridge(types), []);
    });
    it.each([
        { types: '', path: 'value' },
        { types: 'product object', path: 'product.price' },
        { types: 'product.price decimal', path: 'product' }
    ])('requires exact types for $path with using', async ({ types, path }) => {
        await check(bridge(types, `${path} -> amount using toDecimal`), [
            `Source field '${path}' used with transformation 'toDecimal' must declare at least one type.`
        ], [path]);
    });
    it('accepts an exact nested declaration', async () => {
        await check(bridge('product.price string | decimal', 'product.price -> amount using toDecimal'), []);
    });
});

describe('source connector type validation', () => {
    it.each(mongodbSupportedTypes)('accepts the MongoDB type %s', async type => {
        await check(bridge(`value ${type}`, 'value -> amount', 'MongoDB', 'MongoDB'), []);
    });

    it.each(['Pepito', 'String', 'STRING', 'Decimal', 'CUSTOM_TYPE', '_internalType', 'null', 'geoPoint'])('marks the unsupported source type %s exactly', async type => {
        const input = bridge(`value ${type}`, 'name -> name');
        const result = await check(input, [`Source technology 'MongoDB' does not support declared type '${type}' for field 'value'.`], [type]);
        expect(result.document.textDocument.offsetAt(result.diagnostics[0].range.start)).toBe(input.indexOf(type));
        expect(result.document.textDocument.offsetAt(result.diagnostics[0].range.end)).toBe(input.indexOf(type) + type.length);
    });

    it.each([
        { source: 'MongoDB', mapping: 'value -> amount' },
        { source: 'DynamoDB', mapping: 'value -> amount' },
        { source: 'MongoDB', mapping: 'value -> amount using toDecimal' },
        { source: 'DynamoDB', mapping: 'value -> amount using toDecimal' }
    ])('checks descriptor compatibility for $source and $mapping', async ({ source, mapping }) => {
        const transformed = mapping.includes('using');
        await check(bridge('value Pepito', mapping, source), [
            `Source technology '${source}' does not support declared type 'Pepito' for field 'value'.`,
            transformed
                ? "Transformation 'toDecimal' cannot accept type 'Pepito' declared for source field 'value'."
                : "Target technology 'DynamoDB' does not support declared type 'Pepito' mapped from source field 'value'."
        ], ['Pepito', transformed ? 'Pepito' : 'amount']);
    });

    it('reports one source support diagnostic per token across declarations', async () => {
        await check(bridge('value Pepito | String other CUSTOM_TYPE', 'name -> name'), [
            "Source technology 'MongoDB' does not support declared type 'Pepito' for field 'value'.",
            "Source technology 'MongoDB' does not support declared type 'String' for field 'value'.",
            "Source technology 'MongoDB' does not support declared type 'CUSTOM_TYPE' for field 'other'."
        ], ['Pepito', 'String', 'CUSTOM_TYPE']);
    });

    it('marks the second duplicate even for unsupported type names', async () => {
        const input = bridge('value geoPoint | geoPoint', 'name -> name');
        const result = await check(input, [
            "Source technology 'MongoDB' does not support declared type 'geoPoint' for field 'value'.",
            "Type 'geoPoint' is declared more than once for source field 'value'."
        ], ['geoPoint', 'geoPoint']);
        const duplicate = result.diagnostics.find(d => d.message === "Type 'geoPoint' is declared more than once for source field 'value'.")!;
        expect(result.document.textDocument.offsetAt(duplicate.range.start)).toBe(input.lastIndexOf('geoPoint'));
        const unsupportedOffsets = result.diagnostics.filter(d => d.message === "Source technology 'MongoDB' does not support declared type 'geoPoint' for field 'value'.")
            .map(d => result.document.textDocument.offsetAt(d.range.start)).sort((a, b) => a - b);
        expect(unsupportedOffsets).toEqual([input.indexOf('geoPoint')]);
    });

    it('keeps source support diagnostics independent for different fields', async () => {
        const input = bridge('price Pepito stock Pepito', 'name -> name');
        const result = await check(input, [
            "Source technology 'MongoDB' does not support declared type 'Pepito' for field 'price'.",
            "Source technology 'MongoDB' does not support declared type 'Pepito' for field 'stock'."
        ], ['Pepito', 'Pepito']);
        const offsets = result.diagnostics.map(d => result.document.textDocument.offsetAt(d.range.start)).sort((a, b) => a - b);
        expect(offsets).toEqual([input.indexOf('Pepito'), input.lastIndexOf('Pepito')]);
    });

    it('checks source compatibility once across duplicate field declarations', async () => {
        const input = bridge('price Pepito price Pepito', 'name -> name');
        const result = await check(input, [
            "Source technology 'MongoDB' does not support declared type 'Pepito' for field 'price'.",
            "Source field 'price' has more than one type declaration."
        ], ['Pepito', 'price']);
        const unsupported = result.diagnostics.find(d => d.message === "Source technology 'MongoDB' does not support declared type 'Pepito' for field 'price'.")!;
        expect(result.document.textDocument.offsetAt(unsupported.range.start)).toBe(input.indexOf('Pepito'));
    });

    it('reports independent source and transformation errors once alongside a duplicate', async () => {
        const input = bridge('price Pepito | Pepito', 'price -> amount using toDecimal');
        const result = await check(input, [
            "Source technology 'MongoDB' does not support declared type 'Pepito' for field 'price'.",
            "Transformation 'toDecimal' cannot accept type 'Pepito' declared for source field 'price'.",
            "Type 'Pepito' is declared more than once for source field 'price'."
        ], ['Pepito', 'Pepito', 'Pepito']);
        for (const diagnostic of result.diagnostics) {
            const expectedOffset = diagnostic.message === "Type 'Pepito' is declared more than once for source field 'price'."
                ? input.lastIndexOf('Pepito') : input.indexOf('Pepito');
            expect(result.document.textDocument.offsetAt(diagnostic.range.start)).toBe(expectedOffset);
        }
    });

    it('validates each unsupported transformation input', async () => {
        await check(bridge('value Pepito | object', 'value -> amount using toDecimal'), [
            "Source technology 'MongoDB' does not support declared type 'Pepito' for field 'value'.",
            "Transformation 'toDecimal' cannot accept type 'Pepito' declared for source field 'value'.",
            "Transformation 'toDecimal' cannot accept type 'object' declared for source field 'value'."
        ], ['Pepito', 'Pepito', 'object']);
    });

    it('checks all declared types against source and target descriptors', async () => {
        await check(bridge('value Pepito | timestamp', 'value -> amount', 'DynamoDB'), [
            "Source technology 'DynamoDB' does not support declared type 'Pepito' for field 'value'.",
            "Target technology 'DynamoDB' does not support declared type 'Pepito' mapped from source field 'value'.",
            "Source technology 'DynamoDB' does not support declared type 'timestamp' for field 'value'.",
            "Target technology 'DynamoDB' does not support declared type 'timestamp' mapped from source field 'value'."
        ], ['Pepito', 'timestamp', 'amount', 'amount']);
    });

    it('skips source support diagnostics for an unknown source connector', async () => {
        await check(bridge('value geoPoint', 'name -> name', 'UnknownDB'), [
            "Unknown connector technology 'UnknownDB'."
        ], ['UnknownDB']);
    });
});

describe('transformation input compatibility', () => {
    it('checks a repeated incompatible input only once at its first token', async () => {
        const input = bridge('value object | object', 'value -> amount using toDecimal');
        const result = await check(input, [
            "Transformation 'toDecimal' cannot accept type 'object' declared for source field 'value'.",
            "Type 'object' is declared more than once for source field 'value'."
        ], ['object', 'object']);
        for (const diagnostic of result.diagnostics) {
            const expectedOffset = diagnostic.message === "Type 'object' is declared more than once for source field 'value'."
                ? input.lastIndexOf('object') : input.indexOf('object');
            expect(result.document.textDocument.offsetAt(diagnostic.range.start)).toBe(expectedOffset);
        }
    });
    it.each(['string', 'integer', 'decimal', 'boolean', 'binary', 'timestamp'])('toString accepts %s', async type => {
        await check(bridge(`value ${type}`, 'value -> amount using toString'), []);
    });
    it.each(['toInteger', 'toDecimal'])('%s accepts every numeric input together', async transformation => {
        await check(bridge('value string | integer | decimal', `value -> amount using ${transformation}`), []);
    });
    it.each(['toString', 'toInteger', 'toDecimal'])('%s rejects object and array independently', async transformation => {
        await check(bridge('value string | object | array', `value -> amount using ${transformation}`), [
            `Transformation '${transformation}' cannot accept type 'object' declared for source field 'value'.`,
            `Transformation '${transformation}' cannot accept type 'array' declared for source field 'value'.`
        ], ['object', 'array']);
    });
    it('rejects one incompatible type', async () => {
        await check(bridge('value boolean', 'value -> amount using toInteger'), [
            "Transformation 'toInteger' cannot accept type 'boolean' declared for source field 'value'."
        ], ['boolean']);
    });
    it.each(['pepito', 'TOINTEGER'])('unknown %s does not cascade even without types', async transformation => {
        await check(bridge('', `value -> amount using ${transformation}`), [`Unknown transformation '${transformation}'.`], [transformation]);
    });
});

describe('connector logical type compatibility', () => {
    it('checks a repeated passthrough type only once at the target', async () => {
        await check(bridge('value timestamp | timestamp', 'value -> createdAt'), [
            "Target technology 'DynamoDB' does not support declared type 'timestamp' mapped from source field 'value'.",
            "Type 'timestamp' is declared more than once for source field 'value'."
        ], ['createdAt', 'timestamp']);
    });
    it.each(mongodbSupportedTypes)('MongoDB represents %s in both roles', async type => {
        await check(bridge(`value ${type}`, 'value -> amount', 'MongoDB', 'MongoDB'), []);
    });
    it.each(dynamodbSupportedTypes)('DynamoDB represents %s in both roles', async type => {
        await check(bridge(`value ${type}`, 'value -> amount', 'DynamoDB', 'DynamoDB'), []);
    });
    it('rejects even unused timestamps on a DynamoDB source', async () => {
        await check(bridge('createdAt timestamp', 'value -> amount', 'DynamoDB'), [
            "Source technology 'DynamoDB' does not support declared type 'timestamp' for field 'createdAt'."
        ], ['timestamp']);
    });
    it('rejects unchanged timestamps on DynamoDB target', async () => {
        await check(bridge('createdAt string | timestamp', 'createdAt -> createdAt'), [
            "Target technology 'DynamoDB' does not support declared type 'timestamp' mapped from source field 'createdAt'."
        ], ['createdAt']);
    });
    it('accepts timestamps transformed to strings on DynamoDB target', async () => {
        await check(bridge('createdAt timestamp', 'createdAt -> createdAt using toString'), []);
    });
    it('does not cascade for unknown source connectors', async () => {
        await check(bridge('value timestamp', 'value -> amount using toString', 'UnknownDB'), ["Unknown connector technology 'UnknownDB'."], ['UnknownDB']);
    });
    it.each(['', 'using toString'])('does not cascade for unknown target connectors: %s', async using => {
        await check(bridge('value timestamp', `value -> amount ${using}`, 'MongoDB', 'UnknownDB'), ["Unknown connector technology 'UnknownDB'."], ['UnknownDB']);
    });
});
