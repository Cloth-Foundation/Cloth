using Cloth.File;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Cloth.Token {
	public class TokenSpan {

		private int Start {
			get; 
		}

		private int End {
			get;
		}

		private int StartLine {
			get;
		}

		private int EndLine {
			get;
		}

		private int StartColumn {
			get;
		}

		private int EndColumn {
			get;
		}

		private ClothFile File {
			get; 
		}

		public TokenSpan () {
			this.File = null;
		}

		public TokenSpan (int start, int end, int startLine, int endLine, int startColumn, int endColumn, ClothFile file) {
			this.Start = start;
			this.End = end;
			this.StartLine = startLine;
			this.EndLine = endLine;
			this.StartColumn = startColumn;
			this.EndColumn = endColumn;
			this.File = file;
		}

	}
}
