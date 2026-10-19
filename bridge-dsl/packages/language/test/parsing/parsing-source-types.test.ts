import type { BridgeModel } from '../../src/generated/ast.js';
import { createBridgeServices } from '../../src/bridge-module.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, parseHelper } from 'langium/test';
import { afterEach, describe, expect, it } from 'vitest';

const representativeTypeNames = ['string', 'integer', 'decimal', 'boolean', 'binary', 'object', 'array', 'timestamp'] as const;

const services = createBridgeServices(EmptyFileSystem);
const parse = parseHelper<BridgeModel>(services.Bridge);
afterEach(async () => { await clearDocuments(services.shared); });

function bridge(block: string, mappings = 'name -> name'): string {
    return `bridge Types { source MongoDB {} target DynamoDB {} ${block} mapping { ${mappings} } }`;
}

describe('sourceTypes parsing', () => {
    it('keeps sourceTypes optional', async () => {
        const document = await parse(bridge(''));
        expect(document.parseResult.parserErrors).toEqual([]);
        expect(document.parseResult.value.sourceTypes).toBeUndefined();
    });

    it.each([
        { declarations: 'price string', paths: [['price']], types: [['string']] },
        { declarations: 'price string | integer | decimal', paths: [['price']], types: [['string', 'integer', 'decimal']] },
        { declarations: 'product object product.price decimal category.name string', paths: [['product'], ['product', 'price'], ['category', 'name']], types: [['object'], ['decimal'], ['string']] },
        { declarations: `value ${representativeTypeNames.join(' | ')}`, paths: [['value']], types: [[...representativeTypeNames]] },
        { declarations: '', paths: [], types: [] }
    ])('parses declarations: $declarations', async ({ declarations, paths, types }) => {
        const document = await parse(bridge(`sourceTypes { ${declarations} }`));
        expect(document.parseResult.lexerErrors).toEqual([]);
        expect(document.parseResult.parserErrors).toEqual([]);
        expect(document.parseResult.value.sourceTypes?.declarations.map(d => d.path.parts)).toEqual(paths);
        expect(document.parseResult.value.sourceTypes?.declarations.map(d => d.types)).toEqual(types);
    });

    it('parses partial types with mixed normal and transformed mappings before policies', async () => {
        const document = await parse(bridge('sourceTypes { price string } unmapped drop nulls omit', 'name -> name price -> amount using toDecimal'));
        expect(document.parseResult.parserErrors).toEqual([]);
        expect(document.parseResult.value.mapping.mappings.map(m => m.transformation)).toEqual([undefined, 'toDecimal']);
    });

    it.each([
        'sourceTypes { price }', 'sourceTypes { price "string" }',
        'sourceTypes { price string | }', 'sourceTypes { price | string }',
        'sourceTypes { price string, decimal }', 'sourceTypes { price string } sourceTypes {}'
    ])('rejects invalid syntax: %s', async block => {
        const document = await parse(bridge(block));
        expect(document.parseResult.lexerErrors.length + document.parseResult.parserErrors.length).toBeGreaterThan(0);
    });

    it.each(['geoPoint', 'CUSTOM_TYPE', '_internalType', 'null'])('parses an open type name: %s', async type => {
        const document = await parse(bridge(`sourceTypes { price ${type} }`));
        expect(document.parseResult.lexerErrors).toEqual([]);
        expect(document.parseResult.parserErrors).toEqual([]);
        expect(document.parseResult.value.sourceTypes?.declarations[0].types).toEqual([type]);
    });

    it('keeps type-like identifiers usable as field paths and config names', async () => {
        const document = await parse(`bridge Types {
            source MongoDB { string "value" } target DynamoDB {}
            sourceTypes { object.string string }
            mapping { object.string -> timestamp }
        }`);
        expect(document.parseResult.parserErrors).toEqual([]);
        expect(document.parseResult.value.sourceTypes?.declarations[0].path.parts).toEqual(['object', 'string']);
    });
});
