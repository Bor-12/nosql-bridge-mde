import type { ConnectorDescriptor } from './connector-descriptor.js';
import { mongodbDescriptor } from './mongodb-descriptor.js';
import { dynamodbDescriptor } from './dynamodb-descriptor.js';

const connectorRegistry: ReadonlyMap<string, ConnectorDescriptor> = new Map<string, ConnectorDescriptor>([
    ['mongodb', mongodbDescriptor],
    ['dynamodb', dynamodbDescriptor]
]);

export function getConnectorDescriptor(technology: string): ConnectorDescriptor | undefined {
    return connectorRegistry.get(technology.toLowerCase());
}
