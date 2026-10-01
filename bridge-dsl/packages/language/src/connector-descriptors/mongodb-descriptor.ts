import type { ConnectorDescriptor } from './connector-descriptor.js';

export const mongodbDescriptor: ConnectorDescriptor = {
    source: {
        requiredProperties: ['database', 'collection'],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    },
    target: {
        requiredProperties: ['database', 'collection'],
        capabilities: {
            dynamicFields: true,
            nullValues: true,
            nestedFields: true
        }
    }
};
