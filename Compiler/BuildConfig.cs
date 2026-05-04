// Copyright (c) 2026.The Cloth contributors.
//
// BuildConfig.cs is part of the Cloth Compiler.
//
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace Compiler;

public class BuildConfig {
	public string ProjectName { get; private set; } = "";
	public string Version { get; private set; } = "";
	public string Output { get; private set; } = "executable";
	public string Target { get; private set; } = "";
	public string Source { get; private set; } = "src";
	public Dictionary<string, string> Dependencies { get; } = new();

	private BuildConfig() {
	}

	public static BuildConfig Parse(string tomlContent) {
		var config = new BuildConfig();
		var currentSection = "";

		foreach (var raw in tomlContent.Split('\n')) {
			var line = raw.Trim();
			if (string.IsNullOrEmpty(line) || line.StartsWith('#')) continue;

			if (line.StartsWith('[') && line.EndsWith(']')) {
				currentSection = line[1..^1].Trim();
				continue;
			}

			var eq = line.IndexOf('=');
			if (eq < 0) continue;

			var key = line[..eq].Trim();
			var value = line[(eq + 1)..].Trim().Trim('"');

			switch (currentSection) {
				case "Project":
					if (key == "name") config.ProjectName = value;
					else if (key == "version") config.Version = value;
					break;
				case "Build":
					if (key == "output") config.Output = value;
					else if (key == "target") config.Target = value;
					else if (key == "source") config.Source = value;
					break;
				case "Dependencies":
					config.Dependencies[key] = value;
					break;
			}
		}

		return config;
	}

	public static BuildConfig FromFile(string path) => Parse(File.ReadAllText(path));
}