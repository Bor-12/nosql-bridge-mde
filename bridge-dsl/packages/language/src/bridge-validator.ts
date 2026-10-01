import type { ValidationAcceptor, ValidationChecks } from 'langium';
import type { BridgeAstType, BridgeModel } from './generated/ast.js';
import type { BridgeServices } from './bridge-module.js';

/**
 * Register custom validation checks.
 */
export function registerValidationChecks(services: BridgeServices) {
    const registry = services.validation.ValidationRegistry;
    const validator = services.validation.BridgeValidator;
    const checks: ValidationChecks<BridgeAstType> = {
        BridgeModel: validator.checkBridgeHasMappings
    };
    registry.register(checks, validator);
}

/**
 * Implementation of custom validations.
 */
export class BridgeValidator {

    checkBridgeHasMappings(model: BridgeModel, accept: ValidationAcceptor): void {
        if (model.mapping.mappings.length === 0) {
            accept('error', 'A bridge must contain at least one mapping.', {
                node: model.mapping
            });
        }
    }
}
