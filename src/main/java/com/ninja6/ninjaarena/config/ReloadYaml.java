package com.ninja6.ninjaarena.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/** A strict YAML document with source lines retained for validation errors. */
final class ReloadYaml {

    static final class Invalid extends Exception {
        Invalid(String message) {
            super(message);
        }
    }

    private final String name;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Node> keys = new LinkedHashMap<>();
    private final Map<String, Object> values = new LinkedHashMap<>();

    static ReloadYaml read(Path path) throws Invalid {
        try {
            return parse(path.getFileName().toString(), Files.readString(path));
        } catch (IOException e) {
            throw new Invalid(path.getFileName() + ": line 1: cannot read file: " + e.getMessage());
        }
    }

    static ReloadYaml parse(String name, String text) throws Invalid {
        ReloadYaml document = new ReloadYaml(name);
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Node root = yaml.compose(new StringReader(text));
            if (root != null) {
                if (!(root instanceof MappingNode)) {
                    throw document.invalid("", "expected a mapping", root);
                }
                document.index("", root, 0);
                document.indexValues("", yaml.load(text));
            }
            return document;
        } catch (MarkedYAMLException e) {
            int line = e.getProblemMark() == null ? 1 : e.getProblemMark().getLine() + 1;
            throw new Invalid(name + ": line " + line + ": " + e.getProblem());
        } catch (RuntimeException e) {
            throw new Invalid(name + ": line 1: " + e.getMessage());
        }
    }

    private ReloadYaml(String name) {
        this.name = name;
    }

    private void index(String path, Node node, int depth) throws Invalid {
        nodes.put(path, node);
        if (depth > 32) {
            throw invalid(path, "nesting is too deep");
        }
        if (node instanceof MappingNode map) {
            Set<String> keys = new HashSet<>();
            for (var entry : map.getValue()) {
                if (!(entry.getKeyNode() instanceof ScalarNode key)
                        || !key.getValue().matches("[a-z0-9-]+")) {
                    throw invalid(path, "keys must be plain setting names", entry.getKeyNode());
                }
                String child = path.isEmpty() ? key.getValue() : path + "." + key.getValue();
                this.keys.put(child, key);
                if (!keys.add(key.getValue())) {
                    nodes.put(child, key);
                    throw invalid(child, "duplicate key");
                }
                index(child, entry.getValueNode(), depth + 1);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (int i = 0; i < sequence.getValue().size(); i++) {
                index(path + "." + i, sequence.getValue().get(i), depth + 1);
            }
        }
    }

    private void indexValues(String path, Object value) {
        values.put(path, value);
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> indexValues(path.isEmpty() ? key.toString() : path + "." + key, item));
        } else if (value instanceof java.util.List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                indexValues(path + "." + i, list.get(i));
            }
        }
    }

    Object value(String path) { return values.get(path); }
    Map<String, Node> nodes() { return nodes; }
    boolean has(String path) { return nodes.containsKey(path); }

    Invalid invalidKey(String path, String reason) {
        return invalid(path, reason, keys.get(path));
    }

    Invalid invalid(String path, String reason) {
        String parent = path;
        while (!nodes.containsKey(parent) && parent.contains(".")) {
            parent = parent.substring(0, parent.lastIndexOf('.'));
        }
        return invalid(path, reason, nodes.get(parent));
    }

    private Invalid invalid(String path, String reason, Node node) {
        int line = node == null || node.getStartMark() == null ? 1 : node.getStartMark().getLine() + 1;
        return new Invalid(name + ": line " + line + ": " + (path.isEmpty() ? "" : path + ": ") + reason);
    }
}
