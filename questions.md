# Questions

## 1. How did you approach solving the problem?

My approach to this problem consisted of two steps:

- Studying the problem
- Making a plan
- Writing the code

I hadn’t done anything like this before, so I first had to dig into the problem a bit. I looked up some resources online
to understand how file downloading in chunks usually works and what the best practices are.

Once I got a good grasp of the background, a plan started to form for how I could implement it as a single Java class.
I also had a few scenarios in mind that I wanted to cover. I didn’t want to make big structural changes to what you’d
already set up, so I decided to stick closely to the original approach. In practice, that meant keeping things like how
the program starts and how the folders are organised the same.

I also had to make some assumptions through the implementation process:

- The remote server can handle the `HEAD` request.
- The remote server always provides the `Content-MD5` header of the downloaded file.
- The remote server occasionally cannot provide the `Content-Length` header of the downloaded file.
- The remote server always responds with `200 (OK)` or `206 (Partial Content)` status codes when a chunk of the file is
  properly returned.
- The remote server always responds with `416 (Requested Range Not Satisfiable)` status code when a chunk of the file
  can't be returned properly. This usually happens, when the user requests a chunk that doesn't exist.
- When the remote server responds with anything outside the 200–299 HTTP status code range, that can be understood as an
  error.
- The remote server always responds 500 (Internal Server Error) or above when some issue is happening during downloading
  a chunk of the file.

## 2. How did you verify your solution works correctly?

I tested the happy path by downloading a file from a URL and comparing the result with the original file. I couldn’t
find any public web service that really fit what I needed for testing, so I ended up writing a quick Spring Boot app
just for that. It could serve a single file and handled both `HEAD` and `GET` requests, with all the headers set up
properly.

The resumable download against an unreliable server was tested by using the server you provided in the `Program.java`
class. I had to start and stop the program several times till teh file was fully downloaded.

I used AI tools (Chat GPT) to find bugs and potential refactoring ideas. It had a few good ideas, but I
haven't introduced every suggestion. Sometimes they were just out of context (or I didn't provide enough context) or the
suggested soultion was treated as issue by SonarQube (static code analysis tool) or I just wanted to leave it as it is
on purpose. A good example for this last one is the exception handling.

And of course, I coverd the main use cases and the edge cases with unit tests.

## 3. How long did you spend on the exercise?

I spent about 4-6 hours with studying the problem and the domain. And about 16–18 hours with writing the code. This
includes the time for writing the Spring boot application used for testing the happy path. I completed the exercise
in five sittings.

## 4. What would you add if you had more time and how?

The main thing I’d change is the exception handling. Throwing a generic Exception isn’t a great practice. In general,
using exceptions to control the normal flow of a program is often seen as an anti-pattern. That’s why I’d prefer to use
success/failure result objects instead — similar to Kotlin’s Result class. Rather than relying on exceptions to signal
errors, a method could just return a Result that clearly shows whether the operation succeeded or failed. But this would
require a complete refactoring of the code. And not just the FileDownloaderImpl class but the classes in the `runner`
folder as well.
