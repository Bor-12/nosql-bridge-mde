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

describe('Required target field mappings', () => {
    it.each([
        {
            name: 'accepts a mapped partition key without an optional sort key',
            config: 'table "Products" partitionKey "id"',
            mappings: '_id -> id\nname -> name'
        },
        {
            name: 'accepts a composite primary key with both fields explicitly mapped',
            config: 'table "Orders" partitionKey "customerId" sortKey "orderDate"',
            mappings: 'customer.id -> customerId\ncreatedAt -> orderDate\ntotal -> total'
        }
    ])('$name', async ({ config, mappings }) => {
        const result = await validate(`
            bridge KeyBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { ${config} }
                mapping { ${mappings} }
            }
        `);

        expect(result.diagnostics).toEqual([]);
    });

    it.each([
        {
            name: 'rejects an unmapped partition key',
            config: 'table "Products" partitionKey "id"',
            declaration: '',
            mappings: 'name -> name',
            missing: [{ property: 'partitionKey', field: 'id' }]
        },
        {
            name: 'rejects an unmapped sort key independently of a mapped partition key',
            config: 'table "Orders" partitionKey "customerId" sortKey "orderDate"',
            declaration: '',
            mappings: 'customer.id -> customerId\ntotal -> total',
            missing: [{ property: 'sortKey', field: 'orderDate' }]
        },
        {
            name: 'reports both missing mappings for a composite primary key',
            config: 'table "Orders" partitionKey "customerId" sortKey "orderDate"',
            declaration: '',
            mappings: 'total -> total',
            missing: [
                { property: 'partitionKey', field: 'customerId' },
                { property: 'sortKey', field: 'orderDate' }
            ]
        },
        {
            name: 'does not treat unmapped preserve as an explicit key mapping',
            config: 'table "Products" partitionKey "id"',
            declaration: 'unmapped preserve',
            mappings: 'name -> name',
            missing: [{ property: 'partitionKey', field: 'id' }]
        },
        {
            name: 'does not treat a parent mapping as an exact field mapping',
            config: 'table "Products" partitionKey "product.id"',
            declaration: '',
            mappings: 'product -> product',
            missing: [{ property: 'partitionKey', field: 'product.id' }]
        },
        {
            name: 'does not treat a child mapping as an exact field mapping',
            config: 'table "Products" partitionKey "product"',
            declaration: '',
            mappings: 'id -> product.id',
            missing: [{ property: 'partitionKey', field: 'product' }]
        }
    ])('$name', async ({ config, declaration, mappings, missing }) => {
        const result = await validate(`
            bridge KeyBridge {
                source MongoDB { database "shop" collection "products" }
                target DynamoDB { ${config} }
                ${declaration}
                mapping { ${mappings} }
            }
        `);

        expect(result.diagnostics).toHaveLength(missing.length);
        expect(result.diagnostics).toEqual(expect.arrayContaining(missing.map(({ property, field }) =>
            expect.objectContaining({
                message: `Target field '${field}' declared by '${property}' must be produced by a mapping.`,
                severity: DiagnosticSeverity.Error
            })
        )));
        expect(result.diagnostics.map(diagnostic => result.document.textDocument.getText(diagnostic.range)).sort())
            .toEqual(missing.map(({ field }) => `"${field}"`).sort());
    });
});
