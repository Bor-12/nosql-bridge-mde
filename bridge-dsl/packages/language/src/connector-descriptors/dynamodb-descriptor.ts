import type { ConnectorDescriptor } from './connector-descriptor.js';

export const dynamodbDescriptor: ConnectorDescriptor = {
    source: {
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