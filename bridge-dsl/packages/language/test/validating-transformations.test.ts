import type { BridgeModel } from '../src/generated/ast.js';
import { createBridgeServices } from '../src/bridge-module.js';
import { EmptyFileSystem } from 'langium';
import { clearDocuments, validationHelper } from 'langium/test';
import { DiagnosticSeverity } from 'vscode-languageserver-types';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';

let services: ReturnType<typeof createBridgeServices>;
let validate: ReturnType<typeof validationHelper<BridgeModel>>;

beforeAll(() => {
    services = createBridgeServices(EmptyFileSystem);
    validate = validationHelper<BridgeModel>(services.Bridge);
});

afterEach(async () => {
    await clearDocuments(services.shared);
});

function bridgeWithMappings(mappings: string, targetConfig = 'table "Products" partitionKey "id"'): string {
    return `
        bridge TransformationBridge {
            source MongoDB { database "shop" collection "products" }
            target DynamoDB { ${targetConfig} }
            sourceTypes { _id string sequence integer a string b string }
            mapping { ${mappings} }
        }
    `;
}

describe('Bridge transformation validation', () => {
    it.each([undefined, 'toString', 'toInteger', 'toDecimal'])('accepts transformation %s on a required partition key', async transformation => {
        const result = await validate(bridgeWithMappings(
            `_id -> id ${transformation === undefined ? '' : `using ${transformation}`}`
        ));

        expect(result.diagnostics).toEqual([]);
    });

    it.each([
        { transformation: 'toIntegre', source: 'price', target: 'price' },
        { transformation: 'unknownTransformation', source: 'price', target: 'price' },
        { transformation: 'TOINTEGER', source: 'stock', target: 'stock' },
        { transformation: 'toDeciml', source: 'product.price', target: 'details.price' },
        { transformation: 'trim', source: 'name', target: 'name' },
        { transformation: 'concat', source: 'name', target: 'name' }
    ])('rejects $transformation on $source -> $target with an exact diagnostic range', async ({ transformation, source, target }) => {
        const input = bridgeWithMappings(`_id -> id\n${source} -> ${target} using ${transformation}`);
        const result = await validate(input);

        expect(result.document.parseResult.lexerErrors).toHaveLength(0);
        expect(result.document.parseResult.parserErrors).toHaveLength(0);
        expect(result.diagnostics).toHaveLength(1);
        const diagnostic = result.diagnostics[0];
        expect(diagnostic.message).toBe(`Unknown transformation '${transformation}'.`);
        expect(diagnostic.severity).toBe(DiagnosticSeverity.Error);
        const start = input.indexOf(`using ${transformation}`) + 'using '.length;
        expect(diagnostic.range).toEqual({
            start: result.document.textDocument.positionAt(start),
            end: result.document.textDocument.positionAt(start + transformation.length)
        });
        expect(result.document.textDocument.getText(diagnostic.range)).toBe(transformation);
    });

    it('accepts transformed partition and sort key mappings', async () => {
        const result = await validate(bridgeWithMappings(
            '_id -> id using toString\nsequence -> orderNumber using toInteger',
            'table "Orders" partitionKey "id" sortKey "orderNumber"'
        ));

        expect(result.diagnostics).toEqual([]);
    });

    it('still requires an exact sort key target with transformed mappings', async () => {
        const result = await validate(bridgeWithMappings(
            '_id -> id using toString\nsequence -> other using toInteger',
            'table "Orders" partitionKey "id" sortKey "orderNumber"'
        ));

        expect(result.diagnostics).toHaveLength(1);
        expect(result.diagnostics[0].message).toBe("Target field 'orderNumber' declared by 'sortKey' must be produced by a mapping.");
        expect(result.diagnostics[0].severity).toBe(DiagnosticSeverity.Error);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe('"orderNumber"');
    });

    it.each([
        {
            mappings: 'a -> id using toString\nb -> id using toInteger',
            message: "Target field 'id' is mapped more than once.",
            rangeText: 'id'
        },
        {
            mappings: '_id -> id\na -> product using toString\nb -> product.name using toString',
            message: "Target mappings 'product' and 'product.name' conflict because one path contains the other.",
            rangeText: 'product.name'
        }
    ])('preserves target path validation: $message', async ({ mappings, message, rangeText }) => {
        const result = await validate(bridgeWithMappings(mappings));

        expect(result.diagnostics).toHaveLength(1);
        expect(result.diagnostics[0].message).toBe(message);
        expect(result.diagnostics[0].severity).toBe(DiagnosticSeverity.Error);
        expect(result.document.textDocument.getText(result.diagnostics[0].range)).toBe(rangeText);
    });
});
