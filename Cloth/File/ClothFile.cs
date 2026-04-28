using System;
using System.IO;
using System.Linq;

namespace Cloth.File {

	public sealed class ClothFile {

		public string Path {
			get;
		}

		public string Name {
			get;
		}

		public string Content {
			get;
			private set;
		}

		public bool IsValid {
			get;
			private set;
		}

		public ClothFile (string path, string name, string content, bool isValid) {
			this.Path = path;
			this.Name = name;
			this.Content = content;
			this.IsValid = isValid;
		}

		public ClothFile (string path, string name) {
			if (!System.IO.File.Exists (path)) {
				throw new FileNotFoundException ("File not found", path);
			}

			string fileEnding = System.IO.Path.GetExtension (path);
			if (!Utilities.VALID_ENDINGS.Contains (fileEnding)) {
				throw new InvalidDataException ($"Invalid file type: {fileEnding}");
			}

			Path = path;
			Name = name;
			Content = string.Empty;
			IsValid = true;
		}

		public static ClothFile FromPath (string path) {
			string name = System.IO.Path.GetFileName(path);
			if (string.IsNullOrWhiteSpace(name)) {
				name = path;
			}

			return new ClothFile (path, name);
		}

		public void Read () {
			Content = System.IO.File.ReadAllText (Path);
		}

		public bool Validate () {
			IsValid = System.IO.File.Exists(Path) && Utilities.VALID_ENDINGS.Contains (System.IO.Path.GetExtension (Path));
			return IsValid;
		}

		public string GetFileEnding () {
			return System.IO.Path.GetExtension (Path);
		}

		public bool IsClothObject () {
			return GetFileEnding() == ".co";
		}

		public bool IsClothLibrary () {
			return GetFileEnding() == ".cl";
		}

		public bool IsClothFile () {
			return IsClothObject() || IsClothLibrary();
		}

		public ClothFileType GetFileType () {
		
			string ending = GetFileEnding();
			if (ending == ".co") {
				return ClothFileType.ClothObject;
			} else if (ending == ".cl") {
				return ClothFileType.ClothLibrary;
			} else {
				throw new InvalidDataException($"Unknown file type: {ending}");
			}

		}

	}

	public enum ClothFileType {
		ClothObject = 0,
		ClothLibrary = 1,
	}

	public static class Utilities {
		public static string[] VALID_ENDINGS = [
			".co",
			".cl"
		];
	}
}
