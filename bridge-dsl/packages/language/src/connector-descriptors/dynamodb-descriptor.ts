import type { ConnectorDescriptor } from './connector-descriptor.js';

const dynamodbSupportedTypes = [
    'string',
    'integer',
    'decimal',
    'boolean',
    'binary',
    'object',
    'array'
] as const;

export const dynamodbDescriptor: ConnectorDescriptor = {
    source: {
        supportedTypes: dynamodbSupportedTypes,
        properties: [
            {
                name: 'table',
                required: true,
                semantic: 'value'
            }
        ],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        supportedTypes: dynamodbSupportedTypes,
        properties: [
            {
                name: 'table',
                required: true,
                semantic: 'value'
            },
            {
                name: 'partitionKey',
                required: true,
                semantic: 'targetField',
                requiresExplicitMapping: true
            },
            {
                name: 'sortKey',
                required: false,
                semantic: 'targetField',
                requiresExplicitMapping: true
            }
        ],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    }
};
