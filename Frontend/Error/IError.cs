// Copyright (c) 2026.The Cloth contributors.
// 
// IError.cs is part of the Cloth Frontend.
// 
// Use, modification, and distribution of this file are governed by the
// license terms provided with the Cloth Compiler source distribution.

namespace FrontEnd.Error;

public interface IError {
	public string ErrorCode();

	public string GetErrorMessage();

	public bool WillExit();

	public int ExitCode() {
		return 1;
	}

	public void Render() {
		Console.Error.WriteLine($"error[{ErrorCode()}]: {GetErrorMessage()}");
		if (WillExit()) {
			Environment.Exit(ExitCode());
		}
	}
}