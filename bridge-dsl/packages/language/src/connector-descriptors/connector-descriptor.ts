export interface ConnectorCapabilities {
    readonly dynamicFields: boolean;
    readonly nullValues: boolean;
    readonly nestedFields: boolean;
}

export interface ConnectorRoleDescriptor {
    readonly requiredProperties: readonly string[];
    readonly capabilities: ConnectorCapabilities;
}

export interface ConnectorDescriptor {
    readonly source: ConnectorRoleDescriptor;
    readonly target: ConnectorRoleDescriptor;
}
