export interface TransformationDescriptor {
    readonly name: string;
    readonly inputTypes: readonly string[];
    readonly outputType: string;
}

const numericInputTypes = Object.freeze(['string', 'integer', 'decimal'] as const);
const transformationRegistry: ReadonlyMap<string, TransformationDescriptor> = new Map<string, TransformationDescriptor>([
    ['toString', Object.freeze({
        name: 'toString',
        inputTypes: Object.freeze(['string', 'integer', 'decimal', 'boolean', 'binary', 'timestamp'] as const),
        outputType: 'string' as const
    })],
    ['toInteger', Object.freeze({ name: 'toInteger', inputTypes: numericInputTypes, outputType: 'integer' as const })],
    ['toDecimal', Object.freeze({ name: 'toDecimal', inputTypes: numericInputTypes, outputType: 'decimal' as const })]
]);

export function getTransformationDescriptor(name: string): TransformationDescriptor | undefined {
    return transformationRegistry.get(name);
}

export function isSupportedTransformation(name: string): boolean {
    return getTransformationDescriptor(name) !== undefined;
}
