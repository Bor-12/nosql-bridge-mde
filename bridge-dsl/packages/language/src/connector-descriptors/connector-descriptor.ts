export interface ConnectorCapabilities {
    readonly dynamicFields: boolean;
    readonly nullValues: boolean;
    readonly nestedFields: boolean;
}

export type ConnectorPropertyDescriptor =
    | {
        readonly name: string;
        readonly required: boolean;
        readonly semantic: 'value';
    }
    | {
        readonly name: string;
        readonly required: boolean;
        readonly semantic: 'targetField';
        readonly requiresExplicitMapping?: boolean;
    };

export interface ConnectorRoleDescriptor {
    readonly properties: readonly ConnectorPropertyDescriptor[];
    readonly capabilities: ConnectorCapabilities;
}

export interface ConnectorDescriptor {
    readonly source: ConnectorRoleDescriptor;
    readonly target: ConnectorRoleDescriptor;
}
