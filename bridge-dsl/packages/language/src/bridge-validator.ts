import type { ValidationAcceptor, ValidationChecks } from 'langium';
import type { BridgeAstType, BridgeModel, Source, Target } from './generated/ast.js';
import type { BridgeServices } from './bridge-module.js';
import { getConnectorDescriptor } from './connector-descriptors/connector-registry.js';

/**
 * Register custom validation checks.
 */
export function registerValidationChecks(services: BridgeServices) {
    const registry = services.validation.ValidationRegistry;
    const validator = services.validation.BridgeValidator;
    const checks: ValidationChecks<BridgeAstType> = {
        BridgeModel: validator.checkBridgeHasMappings,
        Source: [validator.checkConnectorRequirements, validator.checkDuplicateConnectorProperties],
        Target: [validator.checkConnectorRequirements, validator.checkDuplicateConnectorProperties]
    };
    registry.register(checks, validator);
}

/**
 * Implementation of custom validations.
 */
export class BridgeValidator {

    checkDuplicateConnectorProperties(connector: Source | Target, accept: ValidationAcceptor): void {
        const seenNames = new Set<string>();
        for (const property of connector.properties) {
            if (seenNames.has(property.name)) {
                accept('error', `Duplicate connector property '${property.name}'.`, {
                    node: property,
                    property: 'name'
                });
            } else {
                seenNames.add(property.name);
            }
        }
    }

    checkConnectorRequirements(connector: Source | Target, accept: ValidationAcceptor): void {
        const descriptor = getConnectorDescriptor(connector.technology);
        if (!descriptor) {
            accept('error', `Unknown connector technology '${connector.technology}'.`, {
                node: connector,
                property: 'technology'
            });
            return;
        }

        const role = connector.$type === 'Source' ? 'source' : 'target';
        const presentProperties = new Set(connector.properties.map(property => property.name));
        for (const requiredProperty of descriptor[role].requiredProperties) {
            if (!presentProperties.has(requiredProperty)) {
                accept('error', `Missing required property '${requiredProperty}' for ${connector.technology} ${role}.`, {
                    node: connector
                });
            }
        }
    }

    checkBridgeHasMappings(model: BridgeModel, accept: ValidationAcceptor): void {
        if (model.mapping.mappings.length === 0) {
            accept('error', 'A bridge must contain at least one mapping.', {
                node: model.mapping
            });
        }
    }
}
