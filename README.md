![Logo](https://github.com/Cloth-Foundation/.github/blob/main/Logos/PNG/Header%20-%20NO%20BG.png?raw=true)

**Cloth** is a statically typed, object-oriented, general-purpose programming language.
It is currently interpreted, but designed with future compilation in mind.

Cloth focuses on **clarity, accessibility, and expressiveness**—its syntax is clean and minimal, making it approachable for beginners, while still powerful enough to support advanced software engineering practices.

---

## Features

* **Statically Typed** – Strong, explicit types to catch errors early.
* **Object-Oriented** – Classes, inheritance, and encapsulation built in.
* **General-Purpose** – Suitable for applications ranging from scripting to larger systems.
* **Beginner Friendly** – Accessible syntax designed to be intuitive for newcomers.
* **Expressive** – Rich enough to model complex systems without unnecessary verbosity.
* **Interpreted (for now)** – Rapid development and testing cycle with plans for future compilation.

---

## Philosophy

Cloth’s design is guided by three core principles:

1. **Clarity First** – The syntax should read naturally, without clutter or boilerplate.
2. **Accessibility** – New developers can learn Cloth quickly without sacrificing rigor.
3. **Scalability** – A language that grows with the developer, from toy programs to large projects.

---

## Example

```cloth
class Person {
    var name: string
    var age: i32

    func greet(): string {
        ret "Hello, my name is " + self.name
    }
}

func main() {
    let alice = new Person("Alice", 30)
    print(alice.greet())
}
```

---

## Status

* Current stage: **Early Development**
* Execution model: **Interpreter**
* Roadmap: Future plans include a bytecode virtual machine and ahead-of-time compilation.

---

## Goals

* Provide a **modern, clean alternative** to traditional OOP languages.
* Support both **rapid prototyping** and **long-term software projects**.
* Build a strong **standard library** around everyday development needs.
* Encourage a **community-driven ecosystem**.

---

## Documentation

Documentation is a work in progress. Current docs can be found on the [official website](https://cloth.dev).

---

## Contributing

Contributions are welcome! Please check the `CONTRIBUTING.md` file for guidelines.

---

## License

Cloth is open-source and licensed under the **MIT License**.

