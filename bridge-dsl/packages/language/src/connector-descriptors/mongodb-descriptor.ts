import type { ConnectorDescriptor } from './connector-descriptor.js';

export const mongodbDescriptor: ConnectorDescriptor = {
    source: {
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
