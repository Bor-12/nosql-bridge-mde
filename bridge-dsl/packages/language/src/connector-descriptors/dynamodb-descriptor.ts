import type { ConnectorDescriptor } from './connector-descriptor.js';

export const dynamodbDescriptor: ConnectorDescriptor = {
    source: {
        requiredProperties: ['table'],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        requiredProperties: ['table', 'partitionKey'],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    }
};
