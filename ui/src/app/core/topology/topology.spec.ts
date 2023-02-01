import { Topology, TopologyNode, cardinality, emptyNode, findNode, flatten, splitList, validateTopology } from './topology';

const page: TopologyNode = { ...emptyNode('page', 'Page'), children: [] };
const book: Topology = {
  id: 'book',
  name: 'Book',
  builtIn: true,
  root: { ...emptyNode('book', 'Book'), children: [{ min: 1, max: -1, initial: 4, slugPattern: 'page-{n}', node: page }] },
};

describe('topology', () => {
  it('finds and flattens nodes', () => {
    expect(findNode(book.root, 'page')).toBe(page);
    expect(findNode(book.root, 'missing')).toBeUndefined();
    const flat = flatten(book.root);
    expect(flat.map((entry) => [entry.node.key, entry.depth])).toEqual([
      ['book', 0],
      ['page', 1],
    ]);
    expect(flat[1].rule?.initial).toBe(4);
  });

  it('describes cardinalities', () => {
    expect(cardinality(book.root.children[0])).toBe('1 – ∞');
    expect(cardinality({ min: 1, max: 1, initial: 1, node: page })).toBe('exactly 1');
    expect(cardinality({ min: 0, max: 3, initial: 1, node: page })).toBe('0 – 3');
  });

  it('validates topologies', () => {
    expect(validateTopology(book)).toEqual([]);
    const broken: Topology = {
      id: 'Bad id',
      name: ' ',
      builtIn: false,
      root: {
        ...emptyNode('obj', 'Obj'),
        children: [
          { min: 2, max: 1, initial: 1, node: emptyNode('obj', '', 'Collection') },
        ],
      },
    };
    const errors = validateTopology(broken);
    expect(errors).toContain('The name is required.');
    expect(errors.some((error) => error.includes('identifier'))).toBe(true);
    expect(errors.some((error) => error.includes('used twice'))).toBe(true);
    expect(errors.some((error) => error.includes('needs a label'))).toBe(true);
    expect(errors.some((error) => error.includes('cannot contain a collection'))).toBe(true);
    expect(errors.some((error) => error.includes('maximum'))).toBe(true);
  });

  it('splits lists', () => {
    expect(splitList('a, b\nc,,')).toEqual(['a', 'b', 'c']);
  });
});
