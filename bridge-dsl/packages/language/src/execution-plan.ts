import type { BridgeModel, DefaultValue, FieldMapping, FieldPath } from './generated/ast.js';

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
    transformation?: string;
    defaultValue?: MappingDefaultValue;
}

export type MappingDefaultValue =
    | { type: 'string'; value: string }
    | { type: 'integer'; value: number }
    | { type: 'decimal'; value: number }
    | { type: 'boolean'; value: boolean };
function createDefaultValuePlan(defaultValue: DefaultValue): MappingDefaultValue {
    switch (defaultValue.$type) {
        case 'StringDefaultValue':
            return { type: 'string', value: defaultValue.value };
        case 'IntegerDefaultValue':
            return { type: 'integer', value: defaultValue.value };
        case 'DecimalDefaultValue':
            return { type: 'decimal', value: defaultValue.value };
        case 'BooleanDefaultValue':
            return { type: 'boolean', value: defaultValue.value === 'true' };
    }
}

export interface BridgeExecutionPlan {
    planVersion: 1;
    bridgeName: string;
    source: SourcePlan;
    target: TargetPlan;
    readonly unmappedPolicy: 'drop' | 'preserve';
    readonly nullPolicy: 'preserve' | 'omit';
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
    const plan: MappingPlan = {
        source: fieldPathToString(mapping.source),
        target: fieldPathToString(mapping.target)
    };
    if (mapping.transformation !== undefined) {
        plan.transformation = mapping.transformation;
    }
    if (mapping.defaultValue !== undefined) {
        plan.defaultValue = createDefaultValuePlan(mapping.defaultValue);
    }
    return plan;
}

export function createExecutionPlan(model: BridgeModel): BridgeExecutionPlan {
    return {
        planVersion: 1,
        bridgeName: model.name,
        source: createSourcePlan(model),
        target: createTargetPlan(model),
        unmappedPolicy: model.unmapped?.policy ?? 'drop',
        nullPolicy: model.nulls?.policy ?? 'preserve',
        mappings: model.mapping.mappings.map(createMappingPlan)
    };
}
