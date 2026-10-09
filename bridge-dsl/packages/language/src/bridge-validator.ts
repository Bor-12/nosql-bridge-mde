import type { ValidationAcceptor, ValidationChecks } from 'langium';
import type { BridgeAstType, BridgeModel, FieldMapping, FieldPath, Source, SourceTypes, SourceTypeDeclaration, Target } from './generated/ast.js';
import type { BridgeServices } from './bridge-module.js';
import { getConnectorDescriptor } from './connector-descriptors/connector-registry.js';
import { getTransformationDescriptor } from './transformations/transformation-registry.js';

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
            validator.checkConflictingTargetMappings,
            validator.checkSourceTypeSupport,
            validator.checkMappingTypes
        ],
        FieldMapping: [validator.checkTransformation],
        SourceTypes: [validator.checkDuplicateSourceDeclarations],
        SourceTypeDeclaration: [validator.checkDuplicateSourceTypes],
        Source: [validator.checkConnectorRequirements, validator.checkDuplicateConnectorProperties],
        Target: [validator.checkConnectorRequirements, validator.checkDuplicateConnectorProperties]
    };
    registry.register(checks, validator);
}

/**
 * Implementation of custom validations.
 */
export class BridgeValidator {

    checkTransformation(mapping: FieldMapping, accept: ValidationAcceptor): void {
        if (mapping.transformation !== undefined && !getTransformationDescriptor(mapping.transformation)) {
            accept('error', `Unknown transformation '${mapping.transformation}'.`, {
                node: mapping,
                property: 'transformation'
            });
        }
    }

    checkDuplicateSourceDeclarations(sourceTypes: SourceTypes, accept: ValidationAcceptor): void {
        const seen = new Set<string>();
        for (const declaration of sourceTypes.declarations) {
            const path = fieldPathToString(declaration.path);
            if (seen.has(path)) {
                accept('error', `Source field '${path}' has more than one type declaration.`, { node: declaration.path });
            }
            seen.add(path);
        }
    }

    checkDuplicateSourceTypes(declaration: SourceTypeDeclaration, accept: ValidationAcceptor): void {
        const seen = new Set<string>();
        declaration.types.forEach((type, index) => {
            if (seen.has(type)) {
                accept('error', `Type '${type}' is declared more than once for source field '${fieldPathToString(declaration.path)}'.`, {
                    node: declaration, property: 'types', index
                });
            }
            seen.add(type);
        });
    }

    checkSourceTypeSupport(model: BridgeModel, accept: ValidationAcceptor): void {
        const descriptor = getConnectorDescriptor(model.source.technology);
        if (!descriptor) {
            return;
        }
        const checkedTypesByPath = new Map<string, Set<string>>();
        for (const declaration of model.sourceTypes?.declarations ?? []) {
            const path = fieldPathToString(declaration.path);
            let checkedTypes = checkedTypesByPath.get(path);
            if (!checkedTypes) {
                checkedTypes = new Set<string>();
                checkedTypesByPath.set(path, checkedTypes);
            }
            declaration.types.forEach((type, index) => {
                if (checkedTypes.has(type)) {
                    return;
                }
                checkedTypes.add(type);
                if (!descriptor.source.supportedTypes.includes(type)) {
                    accept('error', `Source technology '${model.source.technology}' does not support declared type '${type}' for field '${path}'.`, {
                        node: declaration, property: 'types', index
                    });
                }
            });
        }
    }

    checkMappingTypes(model: BridgeModel, accept: ValidationAcceptor): void {
        const declarations = new Map<string, SourceTypeDeclaration>();
        for (const declaration of model.sourceTypes?.declarations ?? []) {
            const path = fieldPathToString(declaration.path);
            if (!declarations.has(path)) {
                declarations.set(path, declaration);
            }
        }
        const target = getConnectorDescriptor(model.target.technology)?.target;
        for (const mapping of model.mapping.mappings) {
            const sourcePath = fieldPathToString(mapping.source);
            const declaration = declarations.get(sourcePath);
            if (mapping.transformation !== undefined) {
                const transformation = getTransformationDescriptor(mapping.transformation);
                if (!transformation) {
                    continue;
                }
                if (!declaration || declaration.types.length === 0) {
                    accept('error', `Source field '${sourcePath}' used with transformation '${mapping.transformation}' must declare at least one type.`, {
                        node: mapping.source
                    });
                    continue;
                }
                let compatible = true;
                const checkedTypes = new Set<string>();
                declaration.types.forEach((type, index) => {
                    if (checkedTypes.has(type)) {
                        return;
                    }
                    checkedTypes.add(type);
                    if (!transformation.inputTypes.includes(type)) {
                        compatible = false;
                        accept('error', `Transformation '${transformation.name}' cannot accept type '${type}' declared for source field '${sourcePath}'.`, {
                            node: declaration, property: 'types', index
                        });
                    }
                });
                if (compatible && target && !target.supportedTypes.includes(transformation.outputType)) {
                    accept('error', `Target technology '${model.target.technology}' does not support type '${transformation.outputType}' produced by transformation '${transformation.name}' for field '${fieldPathToString(mapping.target)}'.`, {
                        node: mapping, property: 'transformation'
                    });
                }
            } else if (declaration && target) {
                for (const type of new Set(declaration.types)) {
                    if (!target.supportedTypes.includes(type)) {
                        accept('error', `Target technology '${model.target.technology}' does not support declared type '${type}' mapped from source field '${sourcePath}'.`, {
                            node: mapping.target
                        });
                    }
                }
            }
        }
    }

    checkDuplicateConnectorProperties(connector: Source | Target, accept: ValidationAcceptor): void {
        const seenNames = new Set<string>();
        const role = connector.$type === 'Source' ? 'source' : 'target';
        for (const property of connector.properties) {
            if (seenNames.has(property.name)) {
                accept('error', `Property '${property.name}' is declared more than once in ${connector.technology} ${role}.`, {
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
            const message = `Target technology '${model.target.technology}' cannot use 'nulls preserve' because it does not support null values.`;
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
            accept('error', `Target technology '${model.target.technology}' cannot use 'unmapped preserve' because it does not support dynamic fields.`, {
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
                accept('error', `Nested source path '${fieldPathToString(mapping.source)}' is not supported by source technology '${model.source.technology}'.`, {
                    node: mapping.source
                });
            }
            if (targetDescriptor && mapping.target.parts.length > 1 && !targetDescriptor.target.capabilities.nestedFields) {
                accept('error', `Nested target path '${fieldPathToString(mapping.target)}' is not supported by target technology '${model.target.technology}'.`, {
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
                accept('error', `Target field '${configuredTargetField}' declared by '${property.name}' must be produced by a mapping.`, {
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
                accept('error', `Target field '${targetPath}' is mapped more than once.`, {
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
                    accept('error', `Target mappings '${fieldPathToString(parent)}' and '${fieldPathToString(child)}' conflict because one path contains the other.`, {
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
