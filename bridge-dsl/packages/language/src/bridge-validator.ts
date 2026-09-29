import type { ValidationChecks } from 'langium';
import type { BridgeAstType } from './generated/ast.js';
import type { BridgeServices } from './bridge-module.js';

/**
 * Register custom validation checks.
 */
export function registerValidationChecks(services: BridgeServices) {
    const registry = services.validation.ValidationRegistry;
    const validator = services.validation.BridgeValidator;
    const checks: ValidationChecks<BridgeAstType> = {
        // TODO: Declare validators for your properties
        // See doc : https://langium.org/docs/learn/workflow/create_validations/
        /*
        Element: validator.checkElement
        */
    };
    registry.register(checks, validator);
}

/**
 * Implementation of custom validations.
 */
export class BridgeValidator {

    // TODO: Add logic here for validation checks of properties
    // See doc : https://langium.org/docs/learn/workflow/create_validations/
    /*
    checkElement(element: Element, accept: ValidationAcceptor): void {
        // Always accepts
    }
    */
}
