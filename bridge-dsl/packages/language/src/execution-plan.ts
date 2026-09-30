import type { BridgeModel, FieldMapping, FieldPath } from './generated/ast.js';

export interface SourcePlan {
    technology: string;
    [key: string]: string;
}

export interface TargetPlan {
    technology: string;
    [key: string]: string;
}

export interface MappingPlan {
    source: string;
    target: string;
}

export interface BridgeExecutionPlan {
    bridgeName: string;
    source: SourcePlan;
    target: TargetPlan;
    mappings: MappingPlan[];
}

function createSourcePlan(model: BridgeModel): SourcePlan {
    const sourcePlan: SourcePlan = {
        technology: model.source.technology.toLowerCase()
    };

    for (const property of model.source.properties) {
        sourcePlan[property.name] = property.value;
    }

    return sourcePlan;
}

function createTargetPlan(model: BridgeModel): TargetPlan {
    const targetPlan: TargetPlan = {
        technology: model.target.technology.toLowerCase()
    };

    for (const property of model.target.properties) {
        targetPlan[property.name] = property.value;
    }

    return targetPlan;
}

function fieldPathToString(path: FieldPath): string {
    return path.parts.join('.');
}

function createMappingPlan(mapping: FieldMapping): MappingPlan {
    return {
        source: fieldPathToString(mapping.source),
        target: mapping.target
    };
}

export function createExecutionPlan(model: BridgeModel): BridgeExecutionPlan {
    return {
        bridgeName: model.name,
        source: createSourcePlan(model),
        target: createTargetPlan(model),
        mappings: model.mapping.mappings.map(createMappingPlan)
    };
}
