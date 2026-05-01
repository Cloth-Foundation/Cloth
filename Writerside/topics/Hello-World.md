# Hello World

To complete this tutorial, you need to have installed Cloth.

In this tutorial, we will learn how to write a simple program, using Cloth Object files and writing source code, which
we will then compile to produce an executable binary. However, this tutorial is written for beginners, it is not
intended to be a comprehensive introduction to Cloth. The goal is to sketch out the basics of Cloth and avoid getting into too
much detail.

## Setup

To setup a Cloth project, run the following command:

```bash
$ cloth init My-Project
```

> Spaces are not allowed in project names.
> {style="warning"}

Running `cloth init` creates a new project directory with the following structure:

```
My-Project/
├── build.toml
└── src/
```

The `src/` directory is where all your source files will live. The `build.toml` file at the root of your project
describes how the project should be built.

## The Build File

Open `build.toml`. You will see the following:

```toml
[Project]
name = "My-Project"
version = "0.0.1"

[Build]
output = "executable"
target = "x64_86"
source = "src"

[Dependencies]
cloth = "2026.0.1A"
```

This file is the single source of truth for your project. Let's walk through each section:

- **`[Project]`** — the name and version of your project. The version follows a `major.minor.patch` format.
- **`[Build]`** — tells the compiler what to produce. `output = "executable"` means the compiler will produce a
  runnable binary. `target` is the CPU architecture to compile for, and `source` is the directory where the compiler
  looks for your source files.
- **`[Dependencies]`** — lists the libraries your project depends on. The `cloth` entry refers to the Cloth standard
  library, which provides built-in types and functions such as `println`.

You do not need to modify `build.toml` for this tutorial.

## The Entry Point

Every Cloth project has a single entry point: **`Main.co`**. The compiler always looks for this file to know where
execution begins. You need to create it yourself inside `src/`. For this tutorial, create the following directory
structure and file:

```
src/
└── hello/
    └── world/
        └── Main.co
```

Open `Main.co` and add the following:

```cloth
module hello.world;

import cloth.io.IO::{ println };

public class () {

    public Main {
        println("Hello, World!");
    }

    public ~Main() {
    }
}
```

This is the complete Hello World program. We will now go through it line by line.

## The Module Declaration

```cloth
module hello.world;
```

The first statement in every Cloth source file must be a module declaration. It tells the compiler where this file
lives relative to the source directory. The path segments are separated by dots and must match the directory structure
of the file.

Because `Main.co` is located at `src/hello/world/Main.co`, and the source directory is `src/`, the path relative to
`src/` is `hello/world/` — which maps directly to `hello.world`.

> If you move a source file to a different directory, its module declaration must be updated to match.
> {style="note"}

The declaration ends with a semicolon, as do all statements in Cloth.

## Importing

```cloth
import cloth.io.IO::{ println };
```

This line imports the `println` function from the Cloth standard library. Let's break it down:

- `cloth.io.IO` is the module path of the `IO` class inside the standard library.
- `::{ println }` is a **selective import**. Instead of importing everything from `IO`, we are importing only the
  specific identifier `println`. You can import multiple names by separating them with commas:
  `::{ println, readLine }`.

After this import, `println` is available to use directly in this file. Without the import, the compiler would not
know what `println` refers to.

## The Class Declaration

```cloth
public class () {
    ...
}
```

In Cloth, all code lives inside a class. The `public` keyword makes this class visible to the rest of the project.
The class name is inferred from the file name — because this file is `Main.co`, the class is named `Main`. The `()`
is part of the class declaration syntax.

Everything between the opening `{` and closing `}` is the body of the class.

## The Constructor

```cloth
public Main {
    println("Hello, World!");
}
```

The constructor is the code that runs when this class is instantiated. In Cloth, the constructor shares the name of
the class — `Main`. It is marked `public` so it can be called from outside the class.

The body of the constructor contains a single statement:

```cloth
println("Hello, World!");
```

This calls the `println` function we imported earlier, passing the string `"Hello, World!"` as its argument.
`println` writes the given text to the console, followed by a newline. The statement ends with a semicolon.

When the Cloth runtime starts your program, it instantiates the `Main` class, which causes this constructor to run —
printing `Hello, World!` to the console.

## Why a Constructor, Not a Main Function?

If you have programmed in languages like C, Java, or C#, you may be used to a dedicated entry-point function —
typically named `main` — that sits outside of any class and is called by the runtime to start the program. Cloth takes
a different approach.

In Cloth, there is no special `main` function. Instead, the runtime starts your program by **instantiating the `Main`
class**. This means the constructor is the entry point — it is not a special case bolted onto the side of the language,
but a natural consequence of how Cloth treats programs as objects.

This design has a few practical benefits:

- **Consistency.** The entry point follows the same rules as every other class in your codebase. There is no separate
  syntax or special keyword to learn just to start a program.
- **Explicit lifecycle.** The constructor handles startup and the destructor handles shutdown. The full lifetime of your
  program is expressed through the same mechanism used for every other object in Cloth.
- **No static context.** A traditional `main` function is typically static, meaning it exists outside of any object.
  In Cloth, program execution always happens inside an object, which keeps the language model uniform.

You will see this pattern consistently throughout Cloth: rather than adding special-purpose constructs, the language
reuses the object model it already has.

## The Destructor

```cloth
public ~Main() {
}
```

The destructor is the counterpart to the constructor. It is called automatically when the `Main` instance is no longer
needed and is being cleaned up. In Cloth, a destructor is written the same as the constructor name, prefixed with a
tilde (`~`).

The destructor here has an empty body, which is appropriate for this program — there are no resources to release.
Even so, declaring it is good practice, as it makes the lifetime of your class explicit.

## Building and Running

To compile the project, run the following from the project root:

```bash
$ cloth build
```

The compiler will read `build.toml`, locate your source files, and produce a binary in the project output directory.
Once the build succeeds, run the program:

```bash
$ cloth run
```

You should see the following output in your terminal:

```
Hello, World!
```

Congratulations — you have written and run your first Cloth program.
