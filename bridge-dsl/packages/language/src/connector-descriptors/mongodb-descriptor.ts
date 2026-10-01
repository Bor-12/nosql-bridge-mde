import type { ConnectorDescriptor } from './connector-descriptor.js';

export const mongodbDescriptor: ConnectorDescriptor = {
    source: {
        requiredProperties: ['collection'],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        requiredProperties: ['collection'],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    }
};
