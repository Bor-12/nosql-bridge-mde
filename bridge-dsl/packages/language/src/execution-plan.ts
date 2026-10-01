import type { BridgeModel, FieldMapping, FieldPath } from './generated/ast.js';

export interface SourcePlan {
    technology: string;
    config: Record<string, string>;
}

export interface TargetPlan {
    technology: string;
    config: Record<string, string>;
}

export interface MappingPlan {
    source: string;
    target: string;
}

export interface BridgeExecutionPlan {
    planVersion: 1;
    bridgeName: string;
    source: SourcePlan;
    target: TargetPlan;
    mappings: MappingPlan[];
}

function createSourcePlan(model: BridgeModel): SourcePlan {
    const sourcePlan: SourcePlan = {
        technology: model.source.technology.toLowerCase(),
        config: {}
    };

    for (const property of model.source.properties) {
        sourcePlan.config[property.name] = property.value;
    }

    return sourcePlan;
}

function createTargetPlan(model: BridgeModel): TargetPlan {
    const targetPlan: TargetPlan = {
        technology: model.target.technology.toLowerCase(),
        config: {}
    };

    for (const property of model.target.properties) {
        targetPlan.config[property.name] = property.value;
    }

    return targetPlan;
}

function fieldPathToString(path: FieldPath): string {
    return path.parts.join('.');
}

function createMappingPlan(mapping: FieldMapping): MappingPlan {
    return {
        source: fieldPathToString(mapping.source),
        target: fieldPathToString(mapping.target)
    };
}

export function createExecutionPlan(model: BridgeModel): BridgeExecutionPlan {
    return {
        planVersion: 1,
        bridgeName: model.name,
        source: createSourcePlan(model),
        target: createTargetPlan(model),
        mappings: model.mapping.mappings.map(createMappingPlan)
    };
}
