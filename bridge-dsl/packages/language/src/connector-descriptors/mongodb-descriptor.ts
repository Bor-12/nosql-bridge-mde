import type { ConnectorDescriptor } from './connector-descriptor.js';

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

export const mongodbDescriptor: ConnectorDescriptor = {
    source: {
        supportedTypes: mongodbSupportedTypes,
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
        supportedTypes: mongodbSupportedTypes,
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
