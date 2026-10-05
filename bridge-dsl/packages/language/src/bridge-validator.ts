import type { ValidationAcceptor, ValidationChecks } from 'langium';
import type { BridgeAstType, BridgeModel, FieldPath, Source, Target } from './generated/ast.js';
import type { BridgeServices } from './bridge-module.js';
import { getConnectorDescriptor } from './connector-descriptors/connector-registry.js';

/**
 * Register custom validation checks.
 */
export function registerValidationChecks(services: BridgeServices) {
    const registry = services.validation.ValidationRegistry;
    const validator = services.validation.BridgeValidator;
    const checks: ValidationChecks<BridgeAstType> = {
        BridgeModel: [
            validator.checkBridgeHasMappings,
            validator.checkTargetNullValueCapability,
            validator.checkTargetDynamicFieldsCapability,
            validator.checkNestedFieldCapabilities,
            validator.checkRequiredTargetFieldMappings,
            validator.checkDuplicateTargetMappings,
            validator.checkConflictingTargetMappings
        ],
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
        for (const property of descriptor[role].properties) {
            if (property.required && !presentProperties.has(property.name)) {
                accept('error', `Missing required property '${property.name}' for ${connector.technology} ${role}.`, {
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

    checkTargetNullValueCapability(model: BridgeModel, accept: ValidationAcceptor): void {
        const effectiveNullPolicy = model.nulls?.policy ?? 'preserve';
        if (effectiveNullPolicy === 'omit') {
            return;
        }

        const targetDescriptor = getConnectorDescriptor(model.target.technology);
        if (!targetDescriptor) {
            return;
        }

        if (!targetDescriptor.target.capabilities.nullValues) {
            const message = `Target technology '${model.target.technology}' does not support null values required by nulls preserve.`;
            if (model.nulls) {
                accept('error', message, { node: model.nulls, property: 'policy' });
            } else {
                accept('error', message, { node: model.target, property: 'technology' });
            }
        }
    }

    checkTargetDynamicFieldsCapability(model: BridgeModel, accept: ValidationAcceptor): void {
        const effectiveUnmappedPolicy = model.unmapped?.policy ?? 'drop';
        if (effectiveUnmappedPolicy !== 'preserve') {
            return;
        }

        const targetDescriptor = getConnectorDescriptor(model.target.technology);
        if (!targetDescriptor) {
            return;
        }

        if (!targetDescriptor.target.capabilities.dynamicFields) {
            accept('error', `Target technology '${model.target.technology}' does not support dynamic fields required by unmapped preserve.`, {
                node: model.unmapped!,
                property: 'policy'
            });
        }
    }

    checkNestedFieldCapabilities(model: BridgeModel, accept: ValidationAcceptor): void {
        const sourceDescriptor = getConnectorDescriptor(model.source.technology);
        const targetDescriptor = getConnectorDescriptor(model.target.technology);

        for (const mapping of model.mapping.mappings) {
            if (sourceDescriptor && mapping.source.parts.length > 1 && !sourceDescriptor.source.capabilities.nestedFields) {
                accept('error', `Source technology '${model.source.technology}' does not support nested fields required by source path '${fieldPathToString(mapping.source)}'.`, {
                    node: mapping.source
                });
            }
            if (targetDescriptor && mapping.target.parts.length > 1 && !targetDescriptor.target.capabilities.nestedFields) {
                accept('error', `Target technology '${model.target.technology}' does not support nested fields required by target path '${fieldPathToString(mapping.target)}'.`, {
                    node: mapping.target
                });
            }
        }
    }

    checkRequiredTargetFieldMappings(model: BridgeModel, accept: ValidationAcceptor): void {
        if (model.mapping.mappings.length === 0) {
            return;
        }

        const targetDescriptor = getConnectorDescriptor(model.target.technology);
        if (!targetDescriptor) {
            return;
        }

        for (const property of targetDescriptor.target.properties) {
            if (property.semantic !== 'targetField' || property.requiresExplicitMapping !== true) {
                continue;
            }
            const configProperty = model.target.properties.find(config => config.name === property.name);
            if (!configProperty) {
                continue;
            }
            const configuredTargetField = configProperty.value;
            if (!model.mapping.mappings.some(mapping => fieldPathToString(mapping.target) === configuredTargetField)) {
                accept('error', `Target technology '${model.target.technology}' requires field '${configuredTargetField}' configured by property '${property.name}' to be produced by an explicit mapping.`, {
                    node: configProperty,
                    property: 'value'
                });
            }
        }
    }

    checkDuplicateTargetMappings(model: BridgeModel, accept: ValidationAcceptor): void {
        const seenTargetPaths = new Set<string>();
        for (const mapping of model.mapping.mappings) {
            const targetPath = fieldPathToString(mapping.target);
            if (seenTargetPaths.has(targetPath)) {
                accept('error', `Duplicate target mapping '${targetPath}'.`, {
                    node: mapping.target
                });
            } else {
                seenTargetPaths.add(targetPath);
            }
        }
    }

    checkConflictingTargetMappings(model: BridgeModel, accept: ValidationAcceptor): void {
        const previousTargets: FieldPath[] = [];
        for (const mapping of model.mapping.mappings) {
            const currentTarget = mapping.target;
            for (const previousTarget of previousTargets) {
                const [parent, child] = previousTarget.parts.length < currentTarget.parts.length
                    ? [previousTarget, currentTarget]
                    : [currentTarget, previousTarget];
                if (isProperPrefix(parent.parts, child.parts)) {
                    accept('error', `Conflicting target mappings '${fieldPathToString(parent)}' and '${fieldPathToString(child)}'.`, {
                        node: currentTarget
                    });
                }
            }
            previousTargets.push(currentTarget);
        }
    }
}

function isProperPrefix(parentParts: string[], childParts: string[]): boolean {
    return parentParts.length < childParts.length
        && parentParts.every((part, index) => part === childParts[index]);
}

function fieldPathToString(fieldPath: FieldPath): string {
    return fieldPath.parts.join('.');
}
